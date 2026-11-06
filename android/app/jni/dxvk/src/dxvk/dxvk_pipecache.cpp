#include "dxvk_pipecache.h"

#include <cstdio>

#include "../util/log/log.h"
#include "../util/util_string.h"

namespace dxvk {

  /* A 64-bit FNV-1a over the device identity. A cache from another GPU or
   * driver would be rejected by Vulkan anyway; the hash keeps the files
   * apart and matches the pipelines_<hash>.bin naming used by the port. */
  static uint64_t HashDevice(const VkPhysicalDeviceProperties& props) {
    uint64_t h = 1469598103934665603ull;
    auto mix = [&h] (const void* data, size_t size) {
      for (size_t i = 0; i < size; i++) {
        h ^= static_cast<const uint8_t*>(data)[i];
        h *= 1099511628211ull;
      }
    };
    mix(&props.vendorID,    sizeof(props.vendorID));
    mix(&props.deviceID,    sizeof(props.deviceID));
    mix(&props.driverVersion, sizeof(props.driverVersion));
    mix(props.deviceName, strnlen(props.deviceName, VK_MAX_PHYSICAL_DEVICE_NAME_SIZE));
    return h;
  }


  std::string DxvkPipelineCache::cachePath(
    const VkPhysicalDeviceProperties& props) {
    std::string dir = env::getEnvVar("LB_PIPECACHE_DIR");
    while (!dir.empty() && dir.back() == '/')
      dir.pop_back();
    if (dir.empty())
      return "";

    char hash[24];
    std::snprintf(hash, sizeof(hash), "%016llx",
      static_cast<unsigned long long>(HashDevice(props)));
    return dir + "/pipelines_" + hash + ".bin";
  }


  DxvkPipelineCache::DxvkPipelineCache(
    const Rc<vk::DeviceFn>&          vkd,
    const VkPhysicalDeviceProperties& props)
  : m_vkd(vkd), m_path(cachePath(props)),
    m_vendorId(props.vendorID), m_deviceId(props.deviceID) {
    VkPipelineCacheCreateInfo info = { VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO };

    if (!m_path.empty()) {
      std::ifstream file(m_path, std::ios::binary);
      if (file) {
        file.seekg(0, std::ios::end);
        std::streamoff length = file.tellg();
        file.seekg(0, std::ios::beg);
        if (length > 20) {
          /* Not data{ size_t(length) }: on this toolchain the braces parse
           * as an initializer_list<char> - a ONE-BYTE vector - and the read
           * below then walks 1.7 MB through a one-byte heap chunk and off
           * the page (this is what crashed builds 83 and 86 at DxvkObjects
           * construction, previously misread as a corrupt cache file).
           * resize() makes the count a plain method argument instead. */
          std::vector<char> data;
          data.resize(size_t(length));
          file.read(data.data(), static_cast<std::streamsize>(length));
          /* VkPipelineCacheHeaderVersionOne: headerSize, headerVersion,
           * vendorID, deviceID, pipelineCacheUUID. A corrupt or truncated
           * save would crash the driver inside deserialization, so only a
           * header that plausibly matches this device gets through. */
          struct Header {
            uint32_t headerSize, headerVersion, vendorID, deviceID;
            uint8_t  pipelineCacheUUID[16];
          };
          if (file && data.size() >= sizeof(Header)) {
            const Header* header = reinterpret_cast<const Header*>(data.data());
            bool plausible = header->headerSize >= sizeof(Header)
                          && header->headerVersion == 1
                          && header->vendorID == m_vendorId
                          && header->deviceID == m_deviceId;
            if (plausible) {
              m_initial = std::move(data);
              info.initialDataSize = m_initial.size();
              info.pInitialData    = m_initial.data();
              Logger::info(str::format("Port: pipeline cache ", m_path,
                ", loaded ", info.initialDataSize, " bytes"));
            } else {
              Logger::warn("Port: pipeline cache file ignored (bad header)");
            }
          }
        }
      }
    }

    if (m_vkd->vkCreatePipelineCache(m_vkd->device(), &info, nullptr, &m_cache) != VK_SUCCESS) {
      /* The driver refused the saved data (a driver update, say): start a
       * fresh cache this session and drop the stale file. */
      m_initial.clear();
      info.initialDataSize = 0;
      info.pInitialData    = nullptr;
      if (!m_path.empty())
        std::remove(m_path.c_str());
      if (m_vkd->vkCreatePipelineCache(m_vkd->device(), &info, nullptr, &m_cache) != VK_SUCCESS)
        m_cache = VK_NULL_HANDLE;
    }

    if (m_cache != VK_NULL_HANDLE && !m_path.empty()) {
      m_saved = m_initial.size();
      m_thread = dxvk::thread([this] () { runSaveThread(); });
    }
  }


  DxvkPipelineCache::~DxvkPipelineCache() {
    if (m_thread.joinable()) {
      m_stop.store(true);
      {
        std::unique_lock<dxvk::mutex> lock(m_mutex);
        m_cond.notify_all();
      }
      m_thread.join();
      save();
    }

    if (m_cache != VK_NULL_HANDLE)
      m_vkd->vkDestroyPipelineCache(m_vkd->device(), m_cache, nullptr);
  }


  void DxvkPipelineCache::runSaveThread() {
    while (!m_stop.load()) {
      std::unique_lock<dxvk::mutex> lock(m_mutex);
      m_cond.wait_for(lock, std::chrono::seconds(20),
        [this] () { return m_stop.load(); });
      if (m_stop.load())
        return;
      lock.unlock();
      save();
    }
  }


  bool DxvkPipelineCache::save() {
    if (m_cache == VK_NULL_HANDLE || m_path.empty())
      return false;

    size_t size = 0;
    if (m_vkd->vkGetPipelineCacheData(m_vkd->device(), m_cache, &size, nullptr) != VK_SUCCESS
     || size <= m_saved)
      return false;

    /* The cache can SHRINK between the two calls (the driver compacts it),
     * so the second call may write fewer bytes than sized: everything past
     * what it wrote has to go, or the file carries a zero tail that the
     * driver chokes on when loading it back. */
    std::vector<char> data(size);
    VkResult vr = m_vkd->vkGetPipelineCacheData(m_vkd->device(), m_cache, &size, data.data());
    if (vr != VK_SUCCESS && vr != VK_INCOMPLETE)
      return false;
    if (size == 0)
      return false;
    data.resize(size);

    const std::string tmp = m_path + ".tmp";
    {
      std::ofstream file(tmp, std::ios::binary);
      if (!file)
        return false;
      file.write(data.data(), data.size());
    }
    if (std::rename(tmp.c_str(), m_path.c_str()) != 0) {
      std::remove(tmp.c_str());
      return false;
    }

    m_saved = data.size();
    Logger::info(str::format("Port: pipeline cache saved, ", data.size(), " bytes"));
    return true;
  }

}
