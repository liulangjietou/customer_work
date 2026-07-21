import { ref } from 'vue'
import { usePersistedRef } from './useToolStorage'
import { useDebouncedEffect } from './useDebouncedEffect'

export type CodecMode = 'encode' | 'decode'

/**
 * "编码/解码模式切换 + 实时输出"这一类工具（Base64/URL/Hex）共用的状态机：
 * 输入与模式变化 debounce 300ms 后自动重算，出错时只填 error、不抛到组件外。
 */
export function useEncodeDecodeTool(
  toolKey: string,
  encodeFn: (input: string) => string,
  decodeFn: (input: string) => string,
) {
  const mode = usePersistedRef<CodecMode>(`${toolKey}:mode`, 'encode')
  const input = usePersistedRef(`${toolKey}:input`, '')
  const output = ref('')
  const error = ref('')

  useDebouncedEffect([mode, input], () => {
    if (!input.value) {
      output.value = ''
      error.value = ''
      return
    }
    try {
      output.value = mode.value === 'encode' ? encodeFn(input.value) : decodeFn(input.value)
      error.value = ''
    } catch (e) {
      output.value = ''
      error.value = e instanceof Error ? e.message : String(e)
    }
  })

  return { mode, input, output, error }
}
