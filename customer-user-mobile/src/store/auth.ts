import { defineStore } from 'pinia'

const STORAGE_TOKEN_KEY = 'user-token'
const STORAGE_USER_ID_KEY = 'user-id'
const STORAGE_NICKNAME_KEY = 'user-nickname'

interface AuthState {
  token: string | null
  userId: string | null
  nickname: string | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_TOKEN_KEY),
    userId: localStorage.getItem(STORAGE_USER_ID_KEY) || null,
    nickname: localStorage.getItem(STORAGE_NICKNAME_KEY),
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    applyLogin(token: string, userId: string, nickname: string) {
      this.token = token
      this.userId = userId
      this.nickname = nickname
      localStorage.setItem(STORAGE_TOKEN_KEY, token)
      localStorage.setItem(STORAGE_USER_ID_KEY, userId)
      localStorage.setItem(STORAGE_NICKNAME_KEY, nickname)
    },
    clear() {
      this.token = null
      this.userId = null
      this.nickname = null
      localStorage.removeItem(STORAGE_TOKEN_KEY)
      localStorage.removeItem(STORAGE_USER_ID_KEY)
      localStorage.removeItem(STORAGE_NICKNAME_KEY)
    },
  },
})
