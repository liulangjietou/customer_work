import { defineStore } from 'pinia'
import { fetchMyPermissions, logout as logoutApi } from '@/api/auth'
import type { LoginResponse, UserApprovalStatus } from '@/types/api'

const STORAGE_TOKEN_KEY = 'admin-token'
const STORAGE_NICKNAME_KEY = 'admin-nickname'
const STORAGE_USERNAME_KEY = 'admin-username'
const STORAGE_FORCE_CHANGE_PASSWORD_KEY = 'admin-force-change-password'
const STORAGE_APPROVAL_STATUS_KEY = 'admin-approval-status'
const STORAGE_APPROVAL_REMARK_KEY = 'admin-approval-remark'

function storedApprovalStatus(): UserApprovalStatus {
  const value = localStorage.getItem(STORAGE_APPROVAL_STATUS_KEY)
  if (value === null) {
    return 'APPROVED'
  }
  return value === 'PENDING' || value === 'APPROVED' || value === 'REJECTED' ? value : 'PENDING'
}

interface AuthState {
  token: string | null
  nickname: string | null
  /** 当前登录用户名。后端 LoginResponse 不带这个字段（只有 token/nickname），从登录表单里带出来存一份，
   * 供「系统管理 - 用户管理」编辑用户后判断是不是在改自己、需要同步刷新右上角昵称缓存。 */
  username: string | null
  forceChangePassword: boolean
  approvalStatus: UserApprovalStatus
  approvalRemark: string | null
  permissions: string[]
  /** 每次应用或清理登录都递增，识别同一凭据退出后重新登录的生命周期。 */
  loginGeneration: number
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_TOKEN_KEY),
    nickname: localStorage.getItem(STORAGE_NICKNAME_KEY),
    username: localStorage.getItem(STORAGE_USERNAME_KEY),
    // 必须持久化，不能只活在内存里：若只存内存，浏览器在改密页意外刷新（如表单原生提交触发的整页刷新）
    // 会让这个强制改密的门禁悄悄消失，用户绕过强制改密直接进入系统。
    forceChangePassword: localStorage.getItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY) === 'true',
    // 兼容升级前已存在的登录态：旧缓存没有审核字段，而既有账号迁移后默认均为 APPROVED。
    approvalStatus: storedApprovalStatus(),
    approvalRemark: localStorage.getItem(STORAGE_APPROVAL_REMARK_KEY),
    permissions: [],
    loginGeneration: 0,
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    isApproved: (state) => state.approvalStatus === 'APPROVED',
  },
  actions: {
    applyLoginResult(result: LoginResponse, username: string) {
      this.loginGeneration += 1
      this.permissions = []
      this.token = result.token
      this.nickname = result.nickname
      this.username = username
      this.forceChangePassword = result.forceChangePassword
      this.approvalStatus = result.approvalStatus
      this.approvalRemark = result.approvalRemark
      localStorage.setItem(STORAGE_TOKEN_KEY, result.token)
      localStorage.setItem(STORAGE_NICKNAME_KEY, result.nickname)
      localStorage.setItem(STORAGE_USERNAME_KEY, username)
      localStorage.setItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY, String(result.forceChangePassword))
      localStorage.setItem(STORAGE_APPROVAL_STATUS_KEY, result.approvalStatus)
      if (result.approvalRemark) {
        localStorage.setItem(STORAGE_APPROVAL_REMARK_KEY, result.approvalRemark)
      } else {
        localStorage.removeItem(STORAGE_APPROVAL_REMARK_KEY)
      }
    },
    /** 「用户管理」编辑资料时若改的是当前登录用户自己，用这个同步刷新右上角展示的昵称缓存，
     * 否则要等下次重新登录才会更新——旧 bug：编辑自己的昵称后页面仍显示登录时缓存的旧值。 */
    updateNickname(nickname: string) {
      this.nickname = nickname
      localStorage.setItem(STORAGE_NICKNAME_KEY, nickname)
    },
    /** 服务端要求改密时保留凭据，撤销业务权限及在途初始化，刷新仍停留在改密态。 */
    requirePasswordChange() {
      this.loginGeneration += 1
      this.permissions = []
      this.forceChangePassword = true
      localStorage.setItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY, 'true')
    },
    clearForceChangePassword() {
      this.forceChangePassword = false
      localStorage.removeItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY)
    },
    async loadPermissions() {
      const generation = this.loginGeneration
      const token = this.token
      const permissions = await fetchMyPermissions()
      // 旧成功响应同样不能越过登录边界；新账号尚未加载权限时也保持空权限。
      if (generation === this.loginGeneration && token === this.token) {
        this.permissions = permissions
      }
    },
    hasPermission(permCode: string): boolean {
      return this.permissions.includes(permCode)
    },
    clear() {
      this.loginGeneration += 1
      this.token = null
      this.nickname = null
      this.username = null
      this.forceChangePassword = false
      this.approvalStatus = 'APPROVED'
      this.approvalRemark = null
      this.permissions = []
      localStorage.removeItem(STORAGE_TOKEN_KEY)
      localStorage.removeItem(STORAGE_NICKNAME_KEY)
      localStorage.removeItem(STORAGE_USERNAME_KEY)
      localStorage.removeItem(STORAGE_FORCE_CHANGE_PASSWORD_KEY)
      localStorage.removeItem(STORAGE_APPROVAL_STATUS_KEY)
      localStorage.removeItem(STORAGE_APPROVAL_REMARK_KEY)
    },
    async logout() {
      const generation = this.loginGeneration
      const token = this.token
      try {
        await logoutApi()
      } finally {
        if (generation === this.loginGeneration && token === this.token) this.clear()
      }
    },
  },
})
