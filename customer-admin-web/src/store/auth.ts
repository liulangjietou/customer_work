import { defineStore } from 'pinia'
import { fetchMyPermissions, logout as logoutApi } from '@/api/auth'
import type { LoginResponse } from '@/types/api'

const STORAGE_TOKEN_KEY = 'admin-token'
const STORAGE_NICKNAME_KEY = 'admin-nickname'
const STORAGE_USERNAME_KEY = 'admin-username'
const STORAGE_FORCE_CHANGE_PASSWORD_KEY = 'admin-force-change-password'

interface AuthState {
  token: string | null
  nickname: string | null
  /** 当前登录用户名。后端 LoginResponse 不带这个字段（只有 token/nickname），从登录表单里带出来存一份，
   * 供「系统管理 - 用户管理」编辑用户后判断是不是在改自己、需要同步刷新右上角昵称缓存。 */
  username: string | null
  forceChangePassword: boolean
  permissions: string[]
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_TOKEN_KEY),
    nickname: localStorage.getItem(STORAGE_NICKNAME_KEY),
    username: localStorage.getItem(STORAGE_USERNAME_KEY),
    // 必须持久化，不能只活在内存里：若只存内存，浏览器在改密页意外刷新（如表单原生提交触发的整页刷新）
    // 会让这个强制改密的门禁悄悄消失，用户绕过强制改密直接进入系统。
    forceChangePassword: localStorage.getItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY) === 'true',
    permissions: [],
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    applyLoginResult(result: LoginResponse, username: string) {
      this.token = result.token
      this.nickname = result.nickname
      this.username = username
      this.forceChangePassword = result.forceChangePassword
      localStorage.setItem(STORAGE_TOKEN_KEY, result.token)
      localStorage.setItem(STORAGE_NICKNAME_KEY, result.nickname)
      localStorage.setItem(STORAGE_USERNAME_KEY, username)
      localStorage.setItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY, String(result.forceChangePassword))
    },
    /** 「用户管理」编辑资料时若改的是当前登录用户自己，用这个同步刷新右上角展示的昵称缓存，
     * 否则要等下次重新登录才会更新——旧 bug：编辑自己的昵称后页面仍显示登录时缓存的旧值。 */
    updateNickname(nickname: string) {
      this.nickname = nickname
      localStorage.setItem(STORAGE_NICKNAME_KEY, nickname)
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
      this.username = null
      this.forceChangePassword = false
      this.permissions = []
      localStorage.removeItem(STORAGE_TOKEN_KEY)
      localStorage.removeItem(STORAGE_NICKNAME_KEY)
      localStorage.removeItem(STORAGE_USERNAME_KEY)
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
