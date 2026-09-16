import { ref, watchEffect, type App, type Directive, type Ref, type WatchStopHandle } from 'vue'
import { useAuthStore } from '@/store/auth'

interface PermissionBinding {
  permission: Ref<string>
  apply: () => void
  stop: WatchStopHandle
}

const bindings = new WeakMap<HTMLElement, PermissionBinding>()

/** 按当前权限更新操作入口；按钮可见性属于体验层，实际写入仍由后端权限校验裁决。 */
const permissionDirective: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const permission = ref(binding.value)
    // 独立属性配合全局样式隐藏，保留 Vue 的节点归属，并兼容原有 class/style/v-show。
    const apply = () => el.toggleAttribute('data-permission-denied', !auth.hasPermission(permission.value))
    const stop = watchEffect(apply, { flush: 'sync' })
    bindings.set(el, { permission, apply, stop })
  },
  updated(el, binding) {
    const state = bindings.get(el)
    if (!state) return
    state.permission.value = binding.value
    state.apply()
  },
  beforeUnmount(el) {
    bindings.get(el)?.stop()
    bindings.delete(el)
  },
}

export function installPermissionDirective(app: App) {
  app.directive('permission', permissionDirective)
}
