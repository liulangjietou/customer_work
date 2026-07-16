// highlight.js 代码高亮主题跟随明暗模式切换。
// 两套主题都是全局 .hljs 类选择器，直接 import 两个 css 会互相覆盖，
// 因此用 ?raw 拿到样式文本、注入两个 <style> 标签，按明暗切换 disabled。
import githubLight from 'highlight.js/styles/github.css?raw'
import githubDark from 'highlight.js/styles/github-dark.css?raw'

let lightEl: HTMLStyleElement | null = null
let darkEl: HTMLStyleElement | null = null

function ensureInjected() {
  if (lightEl || typeof document === 'undefined') {
    return
  }
  lightEl = document.createElement('style')
  lightEl.textContent = githubLight
  darkEl = document.createElement('style')
  darkEl.textContent = githubDark
  document.head.append(lightEl, darkEl)
}

/** 按当前明暗状态启用对应的 highlight.js 主题。 */
export function syncHljsTheme(dark: boolean) {
  ensureInjected()
  if (!lightEl || !darkEl) {
    return
  }
  lightEl.disabled = dark
  darkEl.disabled = !dark
}
