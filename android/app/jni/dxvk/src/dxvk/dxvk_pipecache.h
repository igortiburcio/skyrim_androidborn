#pragma once

#include <atomic>
#include <condition_variable>
#include <fstream>
#include <vector>

#include "dxvk_include.h"

#include "../util/sha1/sha1_util.h"
#include "../util/util_env.h"
#include "../util/util_time.h"
#include "../util/thread.h"

namespace dxvk {

  /**
   * \brief Pipeline cache
   *
   * A Vulkan pipeline cache, persisted between launches: the driver's
   * compiled pipelines are loaded from LB_PIPECACHE_DIR at startup and
   * written back every 20 seconds once the cache has grown. Without it,
   * every session recompiles every pipeline - on this port that meant
   * three compiler threads burning the CPU and hitching on each new
   * shader combination.
   */
  class DxvkPipelineCache : public RcObject {

  public:

    DxvkPipelineCache(
      const Rc<vk::DeviceFn>&        vkd,
      const VkPhysicalDeviceProperties& props);

    ~DxvkPipelineCache();

    /**
     * \brief Pipeline cache handle
     * \returns Pipeline cache handle
     */
    VkPipelineCache handle() const {
      return m_cache;
    }

  private:

    Rc<vk::DeviceFn>              m_vkd;
    VkPipelineCache               m_cache  = VK_NULL_HANDLE;

    std::string                   m_path;
    std::vector<char>             m_initial;
    size_t                        m_saved = 0;

    uint32_t                      m_vendorId = 0;
    uint32_t                      m_deviceId = 0;

    dxvk::mutex                   m_mutex;
    dxvk::condition_variable      m_cond;
    std::atomic<bool>             m_stop  = { false };
    dxvk::thread                  m_thread;

    void runSaveThread();

    bool save();

    static std::string cachePath(
      const VkPhysicalDeviceProperties& props);

  };

}
