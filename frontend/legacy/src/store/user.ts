import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { logger } from '@/utils'
import type { UserVO, UserCreditsVO } from '@/api/types'
import { getCurrentUser, getUserCredits } from '@/api/user'
import { login as loginApi, logout as logoutApi, refresh as refreshApi, type LoginPayload } from '@/api/auth'

const ACCESS_TOKEN_KEY = 'aigc:access_token'
const REFRESH_TOKEN_KEY = 'aigc:refresh_token'

export const useUserStore = defineStore('user', () => {
  // State
  const userInfo = ref<UserVO | null>(null)
  const creditInfo = ref<UserCreditsVO | null>(null)
  const loading = ref(false)
  const accessToken = ref<string>(localStorage.getItem(ACCESS_TOKEN_KEY) || '')
  const refreshToken = ref<string>(localStorage.getItem(REFRESH_TOKEN_KEY) || '')
  /** 登录失败后, 前端是否需要展示验证码 */
  const requireCaptcha = ref(false)

  // Getters
  const credits = computed(() => creditInfo.value?.balance ?? userInfo.value?.credits ?? 0)
  const userName = computed(() => userInfo.value?.name ?? '')
  const userAvatar = computed(() => userInfo.value?.avatar ?? '')
  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)
  const hasToken = computed(() => !!accessToken.value)

  // Token 持久化
  function persistTokens(access: string, refresh: string) {
    accessToken.value = access
    refreshToken.value = refresh
    try {
      localStorage.setItem(ACCESS_TOKEN_KEY, access)
      localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
    } catch { /* localStorage 不可用, 内存态兜底 */ }
  }

  function clearAuth() {
    accessToken.value = ''
    refreshToken.value = ''
    userInfo.value = null
    requireCaptcha.value = false
    try {
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch { /* noop */ }
  }

  // Actions
  async function fetchUserInfo() {
    loading.value = true
    try {
      userInfo.value = await getCurrentUser()
    } catch (error) {
      logger.error('Failed to fetch user info:', error)
    } finally {
      loading.value = false
    }
  }

  /** 账密登录 (策略 B 自主鉴权) */
  async function login(payload: LoginPayload) {
    // 后端响应是 snake_case, 读取 access_token / refresh_token
    const result = await loginApi(payload)
    logger.log('[Login] raw response keys:', Object.keys(result as object))
    logger.log('[Login] tokens:', {
      access_token_type: typeof (result as any).access_token,
      has_value: !!(result as any).access_token,
      accessToken_type: typeof (result as any).accessToken,
      has_accessToken: !!(result as any).accessToken,
    })
    persistTokens((result as any).access_token ?? (result as any).accessToken, (result as any).refresh_token ?? (result as any).refreshToken)
    // user info 来自登录响应, 无需再次请求
    const user = (result as any).user
    userInfo.value = {
      id: String(user.id ?? user.userId),
      name: user.real_name ?? user.realName ?? user.phone ?? '',
      phone: user.phone,
      avatar: user.avatar,
      role: user.role,
      ops: user.ops ?? (user.role === 'ops'),
    } as UserVO
    requireCaptcha.value = false
  }

  /** 登出 (撤销服务端 token) */
  async function logout() {
    try { await logoutApi() } catch { /* 即使失败也清前端态 */ }
    clearAuth()
  }

  /** access token 过期时刷新 (401 自动重试).
   *  同时兼容 camelCase/snake_case 响应 */
  async function refreshAccessToken(): Promise<string> {
    if (!refreshToken.value) throw new Error('no refresh token')
    const result = await refreshApi(refreshToken.value) as any
    const access = result.access_token ?? result.accessToken
    const refresh = result.refresh_token ?? result.refreshToken
    persistTokens(access, refresh)
    return access
  }

  async function fetchUserCredits() {
    try {
      creditInfo.value = await getUserCredits()
    } catch (error) {
      logger.error('Failed to fetch user credits:', error)
    }
  }

  // ============ 登录心跳 ============
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  const HEARTBEAT_INTERVAL = 60_000

  /** 启动心跳: 每 60s 调 GET /api/v1/users/me, 401 自动 logout */
  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(async () => {
      if (!accessToken.value) return
      try {
        const me = await getCurrentUser()
        userInfo.value = me
      } catch {
        // 401 / 网络异常 → 静默 logout + 跳登录
        logger.warn('[Heartbeat] session expired, logging out')
        await logout()
        window.location.href = '/login'
      }
    }, HEARTBEAT_INTERVAL)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  return {
    // State
    userInfo,
    creditInfo,
    loading,
    accessToken,
    refreshToken,
    requireCaptcha,

    // Getters
    credits,
    userName,
    userAvatar,
    isLoggedIn,
    hasToken,

    // Actions
    fetchUserInfo,
    fetchUserCredits,
    login,
    logout,
    refreshAccessToken,
    clearAuth,
    startHeartbeat,
    stopHeartbeat,
  }
})