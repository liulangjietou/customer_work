/**
 * 流式对话面板的"跟随到底部"节流器。
 *
 * <p>流式链路每收到一个增量就要求滚动一次。此前两个面板都是直接调
 * {@code scrollTo({ behavior: 'smooth' })}——平滑滚动是一段持续数百毫秒的动画，
 * 而增量的到达间隔远小于它，于是每次调用都在打断上一次动画重新起步，
 * 滚动永远停在缓动曲线最慢的那一段，表现为跟不上内容、一顿一顿。</p>
 *
 * <p>这里改成每渲染帧最多滚一次、且用瞬时定位：对话面板要的是"始终贴着底部"，
 * 不是"看一段滚动动画"。合并掉同一帧内的重复请求后，DOM 读写也从每增量一次降到每帧一次。</p>
 */
export interface ScrollFollower {
  /** 请求滚动到底部；同一帧内的多次调用合并为一次。 */
  follow: () => void
  /** 取消尚未执行的滚动（组件卸载 / 会话切换时调用）。 */
  cancel: () => void
}

type ElementSource = () => HTMLElement | null | undefined

export function createScrollFollower(target: ElementSource): ScrollFollower {
  let frameId: number | null = null

  const run = () => {
    frameId = null
    const el = target()
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  }

  return {
    follow() {
      if (frameId !== null) return
      frameId = typeof requestAnimationFrame === 'function'
        ? requestAnimationFrame(run)
        : (setTimeout(run, 16) as unknown as number)
    },
    cancel() {
      if (frameId === null) return
      if (typeof cancelAnimationFrame === 'function') {
        cancelAnimationFrame(frameId)
      } else {
        clearTimeout(frameId)
      }
      frameId = null
    },
  }
}
