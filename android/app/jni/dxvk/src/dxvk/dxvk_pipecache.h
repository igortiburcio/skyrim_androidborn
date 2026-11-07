#pragma once

#include <atomic>
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <shared_mutex>
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

    /**
     * \brief Locks the cache for pipeline creation
     *
     * vkCreateGraphicsPipelines and vkCreateComputePipelines are internally
     * synchronized with each other for the same cache, so they use a shared
     * lock. vkGetPipelineCacheData requires host access to the cache to be
     * externally synchronized, so the periodic save takes the exclusive
     * lock. Without this, the save thread could read the cache while the
     * render/compiler threads were updating it, which is undefined
     * behaviour and produced transient wrong pipelines (a flat blue weapon
     * silhouette was one visible symptom).
     */
    void lockShared()   { m_cacheLock.lock_shared(); }
    void unlockShared() { m_cacheLock.unlock_shared(); }

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
    std::shared_mutex             m_cacheLock;
    std::atomic<bool>             m_stop  = { false };
    dxvk::thread                  m_thread;

    void runSaveThread();

    bool save();

    static std::string cachePath(
      const VkPhysicalDeviceProperties& props);

  };

}
