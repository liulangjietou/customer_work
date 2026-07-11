import { defineStore } from 'pinia'
import { fetchMyPermissions, logout as logoutApi } from '@/api/auth'
import type { LoginResponse } from '@/types/api'

const STORAGE_TOKEN_KEY = 'admin-token'
const STORAGE_NICKNAME_KEY = 'admin-nickname'
const STORAGE_FORCE_CHANGE_PASSWORD_KEY = 'admin-force-change-password'

interface AuthState {
  token: string | null
  nickname: string | null
  forceChangePassword: boolean
  permissions: string[]
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_TOKEN_KEY),
    nickname: localStorage.getItem(STORAGE_NICKNAME_KEY),
    // 必须持久化，不能只活在内存里：若只存内存，浏览器在改密页意外刷新（如表单原生提交触发的整页刷新）
    // 会让这个强制改密的门禁悄悄消失，用户绕过强制改密直接进入系统。
    forceChangePassword: localStorage.getItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY) === 'true',
    permissions: [],
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    applyLoginResult(result: LoginResponse) {
      this.token = result.token
      this.nickname = result.nickname
      this.forceChangePassword = result.forceChangePassword
      localStorage.setItem(STORAGE_TOKEN_KEY, result.token)
      localStorage.setItem(STORAGE_NICKNAME_KEY, result.nickname)
      localStorage.setItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY, String(result.forceChangePassword))
    },
    clearForceChangePassword() {
      this.forceChangePassword = false
      localStorage.removeItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY)
    },
    async loadPermissions() {
      this.permissions = await fetchMyPermissions()
    },
    hasPermission(permCode: string): boolean {
      return this.permissions.includes(permCode)
    },
    clear() {
      this.token = null
      this.nickname = null
      this.forceChangePassword = false
      this.permissions = []
      localStorage.removeItem(STORAGE_TOKEN_KEY)
      localStorage.removeItem(STORAGE_NICKNAME_KEY)
      localStorage.removeItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY)
    },
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.clear()
      }
    },
  },
})
