/**
 * 正则测试工具的纯函数：枚举匹配、构造高亮 HTML。
 *
 * 是否枚举"全部"匹配严格遵循用户勾选的 flags（不偷偷加 g）——这是刻意的设计选择：
 * 与主流正则测试站点（如 regex101）行为一致，g 未勾选时只出现第一个匹配，不勾选 g 却看到
 * "全部高亮"反而会让用户误判 replace（不带 g 只替换第一个）等其它场景的真实行为。
 */
export function buildRegExp(pattern: string, flags: string): RegExp {
  return new RegExp(pattern, flags)
}

/** 按 flags 枚举匹配：非全局正则只返回第一个匹配（与原生 String.match 行为一致）。 */
export function findAllMatches(re: RegExp, text: string): RegExpExecArray[] {
  const results: RegExpExecArray[] = []
  if (re.global) {
    let m: RegExpExecArray | null
    while ((m = re.exec(text)) !== null) {
      results.push(m)
      if (m[0].length === 0) {
        re.lastIndex += 1 // 零宽匹配（如 /a*/g）不推进 lastIndex 会死循环，手动前移一位
      }
    }
  } else {
    const m = re.exec(text)
    if (m) results.push(m)
  }
  return results
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 把原文按匹配位置切片，未匹配部分转义、匹配部分包 <mark>，拼成可直接 v-html 的字符串。 */
export function buildHighlightHtml(text: string, matches: RegExpExecArray[]): string {
  if (matches.length === 0) return escapeHtml(text)
  let html = ''
  let cursor = 0
  for (const m of matches) {
    const start = m.index
    const end = start + m[0].length
    if (start < cursor) continue // 理论上不会出现重叠，防御一下避免拼出乱序 HTML
    html += escapeHtml(text.slice(cursor, start))
    html += `<mark>${escapeHtml(m[0])}</mark>`
    cursor = end
  }
  html += escapeHtml(text.slice(cursor))
  return html
}

export interface MatchRow {
  index: number
  position: string
  content: string
  groups: string
}

/** 把匹配数组整理成表格行：序号/位置/内容/捕获组（含命名分组）。 */
export function toMatchRows(matches: RegExpExecArray[]): MatchRow[] {
  return matches.map((m, idx) => {
    const start = m.index
    const end = start + m[0].length
    const numberedGroups = m.slice(1).map((g, i) => `$${i + 1}=${g ?? '(未匹配)'}`)
    const namedGroups = m.groups ? Object.entries(m.groups).map(([name, value]) => `${name}=${value ?? '(未匹配)'}`) : []
    const groupParts = [...numberedGroups, ...namedGroups]
    return {
      index: idx + 1,
      position: `${start}-${end}`,
      content: m[0],
      groups: groupParts.length > 0 ? groupParts.join(', ') : '无',
    }
  })
}
