#pragma once

#include <atomic>
#include <condition_variable>
#include <deque>
#include <vector>

#include "../util/thread.h"
#include "rc/util_rc.h"
#include "rc/util_rc_ptr.h"

#include "dxvk_graphics.h"

namespace dxvk {

  /**
   * \brief Async pipeline compiler
   *
   * Compiles graphics pipelines on a small pool of worker threads so the
   * first appearance of a shader combination no longer stalls the frame.
   * The instance is created with a null handle on the requesting thread;
   * a worker compiles it and stores the handle with release semantics.
   * The draw that asked for it is skipped until the handle is ready, so the
   * object shows up a frame or two later.
   */
  class DxvkPipelineCompiler : public RcObject {

  public:

    DxvkPipelineCompiler();
    ~DxvkPipelineCompiler();

    void queueCompilation(
            DxvkGraphicsPipeline*              pipeline,
            DxvkGraphicsPipelineInstance*      instance,
      const DxvkGraphicsPipelineStateInfo&     state,
      const DxvkRenderPass*                    renderPass);

    void stopWorkerThreads();

    bool isCompilingShaders() const {
      return m_workerBusy.load() != 0;
    }

  private:

    struct PipelineEntry {
      DxvkGraphicsPipeline*              pipeline;
      DxvkGraphicsPipelineInstance*      instance;
      DxvkGraphicsPipelineStateInfo      state;
      const DxvkRenderPass*              renderPass;
    };

    std::atomic<uint32_t>         m_workerBusy = { 0u };
    std::atomic<bool>             m_stopped    = { false };

    dxvk::mutex                   m_mutex;
    dxvk::condition_variable      m_cond;
    std::deque<PipelineEntry>     m_queue;

    std::vector<dxvk::thread>     m_workers;

    void runCompilerThread();

  };

}
