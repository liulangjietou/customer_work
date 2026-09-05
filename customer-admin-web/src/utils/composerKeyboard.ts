/** 输入法确认、组合快捷键与长按回车不发送；生成期间仍允许编辑下一条草稿。 */
export function handleComposerKeydown(event: KeyboardEvent, send: () => void, streaming: boolean) {
  if (
    event.key !== 'Enter' ||
    event.isComposing ||
    event.keyCode === 229 ||
    event.shiftKey ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.repeat ||
    streaming
  )
    return
  event.preventDefault()
  send()
}
