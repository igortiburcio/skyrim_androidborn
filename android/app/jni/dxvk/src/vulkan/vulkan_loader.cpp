#include "vulkan_loader.h"

namespace dxvk::vk {

  static PFN_vkGetInstanceProcAddr GetInstanceProcAddr = vkGetInstanceProcAddr;

  PFN_vkGetInstanceProcAddr getVulkanLoader() {
    return GetInstanceProcAddr;
  }

  void setVulkanLoader(PFN_vkGetInstanceProcAddr fn) {
    if (fn)
      GetInstanceProcAddr = fn;
  }

  PFN_vkVoidFunction LibraryLoader::sym(const char* name) const {
    return dxvk::vk::GetInstanceProcAddr(nullptr, name);
  }
  
  
  InstanceLoader::InstanceLoader(bool owned, VkInstance instance)
  : m_instance(instance), m_owned(owned) { }
  
  
  PFN_vkVoidFunction InstanceLoader::sym(const char* name) const {
    return dxvk::vk::GetInstanceProcAddr(m_instance, name);
  }
  
  
  DeviceLoader::DeviceLoader(bool owned, VkInstance instance, VkDevice device)
  : m_getDeviceProcAddr(reinterpret_cast<PFN_vkGetDeviceProcAddr>(
      dxvk::vk::GetInstanceProcAddr(instance, "vkGetDeviceProcAddr"))),
    m_device(device), m_owned(owned) { }
  
  
  PFN_vkVoidFunction DeviceLoader::sym(const char* name) const {
    return m_getDeviceProcAddr(m_device, name);
  }
  
  
  LibraryFn::LibraryFn() { }
  LibraryFn::~LibraryFn() { }
  
  
  InstanceFn::InstanceFn(bool owned, VkInstance instance)
  : InstanceLoader(owned, instance) { }
  InstanceFn::~InstanceFn() {
    if (m_owned)
      this->vkDestroyInstance(m_instance, nullptr);
  }
  
  
  DeviceFn::DeviceFn(bool owned, VkInstance instance, VkDevice device)
  : DeviceLoader(owned, instance, device) { }
  DeviceFn::~DeviceFn() {
    if (m_owned)
      this->vkDestroyDevice(m_device, nullptr);
  }
  
}

/* Called by the runtime after libadrenotools loaded a custom Vulkan driver:
 * every instance and device dxvk creates then goes through that loader. */
extern "C" void skyrim_set_vulkan_loader(void* fn) {
  dxvk::vk::setVulkanLoader(reinterpret_cast<PFN_vkGetInstanceProcAddr>(fn));
}