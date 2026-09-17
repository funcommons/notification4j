import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi } from '@/api/benefitAuth'

export const useBenefitAuthStore = defineStore('benefitAuth', () => {
  const token = ref<string | null>(sessionStorage.getItem('benefit4j:access_token'))
  const tenantId = ref<string | null>(sessionStorage.getItem('benefit4j:tenant_id'))
  const appSecret = ref<string | null>(sessionStorage.getItem('benefit4j:app_secret'))
  const expiresAt = ref<number | null>(
    sessionStorage.getItem('benefit4j:expires_at')
      ? Number(sessionStorage.getItem('benefit4j:expires_at'))
      : null
  )

  const isLoggedIn = computed(() => {
    if (!token.value) return false
    if (expiresAt.value && Date.now() > expiresAt.value) {
      logout()
      return false
    }
    return true
  })

  const isExpiringSoon = computed(() => {
    if (!expiresAt.value) return false
    const fiveMin = 5 * 60 * 1000
    return Date.now() > expiresAt.value - fiveMin
  })

  function setToken(accessToken: string, expiresIn?: number, persist = true) {
    token.value = accessToken
    if (persist) {
      sessionStorage.setItem('benefit4j:access_token', accessToken)
    }
    if (expiresIn) {
      const at = Date.now() + expiresIn * 1000
      expiresAt.value = at
      if (persist) {
        sessionStorage.setItem('benefit4j:expires_at', String(at))
      }
    }
  }

  function setAppId(id: string) {
    tenantId.value = id
    sessionStorage.setItem('benefit4j:tenant_id', id)
  }

  async function login(clientId: string, clientSecret: string) {
    const result = await loginApi(clientId, clientSecret)
    setToken(result.access_token, result.expires_in)
    setAppId(clientId)
    // runtime 域签名需 app_secret (= client_credentials 的 client_secret), 与 token 同生命周期管理
    appSecret.value = clientSecret
    sessionStorage.setItem('benefit4j:app_secret', clientSecret)
  }

  function logout() {
    token.value = null
    tenantId.value = null
    appSecret.value = null
    expiresAt.value = null
    sessionStorage.removeItem('benefit4j:access_token')
    sessionStorage.removeItem('benefit4j:tenant_id')
    sessionStorage.removeItem('benefit4j:app_secret')
    sessionStorage.removeItem('benefit4j:expires_at')
  }

  return {
    token,
    tenantId,
    appSecret,
    isLoggedIn,
    isExpiringSoon,
    setToken,
    setAppId,
    login,
    logout,
  }
})
