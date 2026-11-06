#include "dxvk_pipecompiler.h"

#include "../util/log/log.h"
#include "../util/util_string.h"

namespace dxvk {

  DxvkPipelineCompiler::DxvkPipelineCompiler() {
    // Policy: half the cores minus one, between 1 and 4 workers.
    uint32_t numWorkers = dxvk::thread::hardware_concurrency();
    if (numWorkers < 1) numWorkers = 1;
    numWorkers = (numWorkers - 1) / 2;
    if (numWorkers < 1) numWorkers = 1;
    if (numWorkers > 4) numWorkers = 4;

    Logger::info(str::format("Port: async pipeline compiler, ", numWorkers, " threads"));

    for (uint32_t i = 0; i < numWorkers; i++)
      m_workers.emplace_back([this] () { runCompilerThread(); });
  }


  DxvkPipelineCompiler::~DxvkPipelineCompiler() {
    stopWorkerThreads();
  }


  void DxvkPipelineCompiler::queueCompilation(
          DxvkGraphicsPipeline*              pipeline,
          DxvkGraphicsPipelineInstance*      instance,
    const DxvkGraphicsPipelineStateInfo&     state,
    const DxvkRenderPass*                    renderPass) {
    {
      std::unique_lock<dxvk::mutex> lock(m_mutex);
      m_queue.push_back(PipelineEntry{ pipeline, instance, state, renderPass });
    }
    m_cond.notify_one();
  }


  void DxvkPipelineCompiler::stopWorkerThreads() {
    if (m_stopped.exchange(true))
      return;

    {
      std::unique_lock<dxvk::mutex> lock(m_mutex);
      m_cond.notify_all();
    }

    for (auto& worker : m_workers)
      worker.join();
    m_workers.clear();
  }


  void DxvkPipelineCompiler::runCompilerThread() {
    while (!m_stopped.load()) {
      PipelineEntry entry;

      {
        std::unique_lock<dxvk::mutex> lock(m_mutex);
        m_cond.wait(lock, [this] () {
          return m_stopped.load() || !m_queue.empty();
        });

        if (m_stopped.load() && m_queue.empty())
          return;

        entry = m_queue.front();
        m_queue.pop_front();
      }

      m_workerBusy += 1;

      entry.pipeline->compileInstanceAsync(
        entry.instance, entry.state, entry.renderPass);

      m_workerBusy -= 1;
    }
  }

}
