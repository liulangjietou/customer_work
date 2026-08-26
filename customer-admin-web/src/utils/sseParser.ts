export interface SseEvent {
  event: string
  data: string
}

/**
 * 取 SSE 字段值：按规范只去掉一个协议分隔空格，其余原样保留。
 *
 * 此前这里用 trim()，会把正文增量首尾空格吃掉。模型流按 token 切片时，一个以空格开头的
 * 英文 token 到页面上就会与前一个词粘连，Markdown 缩进也会丢失；正文空格属于有效数据。
 * 同时显式移除 CRLF 留下的行尾 CR，避免把控制字符带进正文。
 */
function readSseFieldValue(rawValue: string): string {
  const withoutCr = rawValue.endsWith('\r') ? rawValue.slice(0, -1) : rawValue
  return withoutCr.startsWith(' ') ? withoutCr.slice(1) : withoutCr
}

/** 把一个完整 SSE 事件块解析为事件名和原始正文；无 data 字段的块直接忽略。 */
export function parseSseBlock(block: string): SseEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      // 事件名是协议标识而非正文，两端空白一律不保留。
      event = readSseFieldValue(line.slice('event:'.length)).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(readSseFieldValue(line.slice('data:'.length)))
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}
