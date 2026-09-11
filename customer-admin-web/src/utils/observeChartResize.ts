/**
 * 图表随自身容器调整大小。导航动画中的多次通知合并到一帧，避免反复布局；
 * 隐藏容器不绘制，重新显示时按新尺寸恢复。返回的清理函数先于图表销毁调用。
 */
export function observeChartResize(element: HTMLElement, resize: () => void): () => void {
  let frame: number | null = null
  let disposed = false
  let previousWidth = 0
  let previousHeight = 0

  function cancelPendingFrame() {
    if (frame === null) return
    cancelAnimationFrame(frame)
    frame = null
  }

  function scheduleResize() {
    if (disposed || frame !== null) return
    frame = requestAnimationFrame(() => {
      frame = null
      if (!disposed) resize()
    })
  }

  if (typeof ResizeObserver === 'undefined') {
    window.addEventListener('resize', scheduleResize)
    return () => {
      disposed = true
      window.removeEventListener('resize', scheduleResize)
      cancelPendingFrame()
    }
  }

  const observer = new ResizeObserver(([entry]) => {
    if (disposed || !entry) return
    const { width, height } = entry.contentRect
    if (width === previousWidth && height === previousHeight) return
    previousWidth = width
    previousHeight = height
    if (width <= 0 || height <= 0) {
      cancelPendingFrame()
      return
    }
    scheduleResize()
  })
  observer.observe(element)

  return () => {
    disposed = true
    observer.disconnect()
    cancelPendingFrame()
  }
}
