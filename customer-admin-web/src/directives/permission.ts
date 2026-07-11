import type { App, Directive } from 'vue'
import { useAuthStore } from '@/store/auth'

/**
 * v-permission="'user:add'"：当前用户没有该权限点时，直接移除元素（不是仅隐藏，避免 DevTools 里
 * 还能看到/改 display 绕过）。按钮级权限隐藏，与后端 @SaCheckPermission 的最终裁决互为表里
 * （前端只是体验层，真正兜底始终是后端接口校验）。
 */
const permissionDirective: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    if (!auth.hasPermission(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}

export function installPermissionDirective(app: App) {
  app.directive('permission', permissionDirective)
}
