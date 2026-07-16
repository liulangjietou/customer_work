import type { App } from 'vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

/**
 * 全量注册 Element Plus 图标为全局组件。
 *
 * 注意：这是刻意保留的全量注册，不做按需引入——MenuTree/MenuManage/IconPicker 里
 * 通过 <component :is="字符串"> 按运行时图标名渲染，静态分析无法覆盖动态场景；
 * 图标库为纯 SVG 组件，体积可接受，不值得引入"图标白名单"的维护负担。
 */
export function installElementPlusIcons(app: App) {
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }
}
