import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import json from 'highlight.js/lib/languages/json'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import bash from 'highlight.js/lib/languages/bash'
import sql from 'highlight.js/lib/languages/sql'
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('json', json)
hljs.registerLanguage('java', java)
hljs.registerLanguage('python', python)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('yaml', yaml)

// markdown-it 内置表格语法（GFM 表格属于其核心规则，无需额外插件）；代码块交给 highlight.js 高亮，
// 语言不识别（或未注册）时退化成纯转义文本，不抛错中断整段渲染。
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  // hljs 主题(github.css)按 .hljs 类选择器生效，markdown-it 默认只加 language-xxx，故拼上 hljs 前缀
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch {
        // fall through to escaped plain text
      }
    }
    return md.utils.escapeHtml(code)
  },
})

export function renderMarkdown(text: string): string {
  return md.render(text ?? '')
}
