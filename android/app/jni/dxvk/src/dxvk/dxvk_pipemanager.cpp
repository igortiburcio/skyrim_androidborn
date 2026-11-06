#include "dxvk_device.h"
#include "dxvk_pipemanager.h"
#include "dxvk_state_cache.h"

namespace dxvk {
  
  DxvkPipelineManager::DxvkPipelineManager(
          DxvkDevice*         device,
          DxvkRenderPassPool* passManager)
  : m_device    (device),
    m_cache     (new DxvkPipelineCache(device->vkd(),
                   device->adapter()->deviceProperties())) {
    std::string useStateCache = env::getEnvVar("DXVK_STATE_CACHE");
    
    if (useStateCache != "0" && device->config().enableStateCache)
      m_stateCache = new DxvkStateCache(device, this, passManager);

    if (device->config().enableAsync) {
      m_compiler = new DxvkPipelineCompiler();
      Logger::info("Port: async graphics pipeline compile ON (dxvk.enableAsync = True)");
    } else {
      Logger::info("Port: async graphics pipeline compile OFF (dxvk.enableAsync = True to enable)");
    }
  }
  
  
  DxvkPipelineManager::~DxvkPipelineManager() {
    this->stopWorkerThreads();
  }
  
  
  DxvkComputePipeline* DxvkPipelineManager::createComputePipeline(
    const DxvkComputePipelineShaders& shaders) {
    if (shaders.cs == nullptr)
      return nullptr;
    
    std::lock_guard<dxvk::mutex> lock(m_mutex);
    
    auto pair = m_computePipelines.find(shaders);
    if (pair != m_computePipelines.end())
      return &pair->second;
    
    auto iter = m_computePipelines.emplace(
      std::piecewise_construct,
      std::tuple(shaders),
      std::tuple(this, shaders));
    return &iter.first->second;
  }
  
  
  DxvkGraphicsPipeline* DxvkPipelineManager::createGraphicsPipeline(
    const DxvkGraphicsPipelineShaders& shaders) {
    if (shaders.vs == nullptr)
      return nullptr;
    
    std::lock_guard<dxvk::mutex> lock(m_mutex);
    
    auto pair = m_graphicsPipelines.find(shaders);
    if (pair != m_graphicsPipelines.end())
      return &pair->second;
    
    auto iter = m_graphicsPipelines.emplace(
      std::piecewise_construct,
      std::tuple(shaders),
      std::tuple(this, shaders));
    return &iter.first->second;
  }

  
  void DxvkPipelineManager::registerShader(
    const Rc<DxvkShader>&         shader) {
    if (m_stateCache != nullptr)
      m_stateCache->registerShader(shader);
  }


  DxvkPipelineCount DxvkPipelineManager::getPipelineCount() const {
    DxvkPipelineCount result;
    result.numComputePipelines  = m_numComputePipelines.load();
    result.numGraphicsPipelines = m_numGraphicsPipelines.load();
    return result;
  }


  bool DxvkPipelineManager::isCompilingShaders() const {
    return (m_stateCache != nullptr
        && m_stateCache->isCompilingShaders())
        || (m_compiler != nullptr
        && m_compiler->isCompilingShaders());
  }


  void DxvkPipelineManager::stopWorkerThreads() const {
    if (m_stateCache != nullptr)
      m_stateCache->stopWorkerThreads();
    if (m_compiler != nullptr)
      m_compiler->stopWorkerThreads();
  }


  void DxvkPipelineManager::queueAsyncCompilation(
          DxvkGraphicsPipeline*              pipeline,
          DxvkGraphicsPipelineInstance*      instance,
    const DxvkGraphicsPipelineStateInfo&     state,
    const DxvkRenderPass*                    renderPass) {
    if (m_compiler != nullptr)
      m_compiler->queueCompilation(pipeline, instance, state, renderPass);
  }
  
}
