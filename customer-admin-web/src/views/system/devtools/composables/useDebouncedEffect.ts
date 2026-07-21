import { watch } from 'vue'
import type { WatchSource } from 'vue'

/**
 * 通用"输入变化 debounce 300ms 后自动重算"效果：只关心"该重算了"这个时机，不关心具体变化了什么值
 * （回调里直接读闭包捕获的 ref.value 即可），因此不需要泛型化 watch 的值类型，一个 sources 数组即可
 * 同时覆盖"多个输入共同驱动一次计算"的场景（如 JSON 工具的 输入文本+格式化模式+缩进位数）。
 *
 * 用于开发者工具箱里"实时计算"类工具（JSON/时间戳/编解码/正则等），显式排除 UUID 生成、AES 加解密
 * 这类"有随机性/参数较多，误触发代价高"的工具——那两个工具按验收要求使用显式按钮，不接入本 effect。
 */
export function useDebouncedEffect(sources: WatchSource<unknown>[], callback: () => void, delay = 300) {
  let timer: ReturnType<typeof setTimeout> | null = null
  watch(
    sources,
    () => {
      if (timer) {
        clearTimeout(timer)
      }
      timer = setTimeout(callback, delay)
    },
    { immediate: true },
  )
}
