/**
 * 把同一渲染帧内到达的文本增量合并后再写入响应式状态。
 *
 * 模型流可能在一帧内推送多次；若每片都改 Pinia，会反复触发 Markdown 解析、DOM patch 与滚动。
 * 这里最多增加一帧延迟，同时保留 flush/discard 两种显式收尾语义。
 */
export interface TextChunkBatcher {
  append: (chunk: string) => void
  flush: () => void
  discard: () => void
}

type CancelScheduledFrame = () => void

function scheduleRenderFrame(callback: () => void): CancelScheduledFrame {
  if (typeof requestAnimationFrame === 'function') {
    const frameId = requestAnimationFrame(callback)
    return () => cancelAnimationFrame(frameId)
  }
  // 测试或非浏览器环境兜底；生产浏览器始终走 requestAnimationFrame。
  const timeoutId = setTimeout(callback, 16)
  return () => clearTimeout(timeoutId)
}

export function createTextChunkBatcher(onFlush: (text: string) => void): TextChunkBatcher {
  let pending = ''
  let cancelScheduledFrame: CancelScheduledFrame | null = null

  const emitPending = () => {
    cancelScheduledFrame = null
    if (!pending) return
    const text = pending
    pending = ''
    onFlush(text)
  }

  return {
    append(chunk: string) {
      if (!chunk) return
      pending += chunk
      if (!cancelScheduledFrame) {
        cancelScheduledFrame = scheduleRenderFrame(emitPending)
      }
    },
    flush() {
      cancelScheduledFrame?.()
      cancelScheduledFrame = null
      emitPending()
    },
    discard() {
      cancelScheduledFrame?.()
      cancelScheduledFrame = null
      pending = ''
    },
  }
}
