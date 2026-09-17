import { http } from './request'

const BASE_URL = '/api/v1/auth'

/** 验证码挑战响应 */
export interface CaptchaChallengeVO {
  captchaId: string
  type: 'altcha' | 'slider' | 'turnstile' | string
  // ALTCHA PoW
  challenge?: string
  signature?: string
  algorithm?: string
  maxnumber?: number
  salt?: string
  // 滑块 (已废弃, 保留兼容)
  backgroundImage?: string
  sliderImage?: string
  yPosition?: number
  expiresIn: number
}

/** 登录响应 (后端 serialize 为 snake_case) */
export interface LoginResultVO {
  /** 访问 token, 注意后端字段名为 snake_case, 与前端默认 camelCase 不一致 */
  access_token: string
  refresh_token: string
  expires_in: number | string
  token_type: string
  user: {
    id: string | number
    phone?: string
    real_name?: string
    avatar?: string
    role: string
    status?: number
  }
}

/** 系统能力 (前端探测用) */
export interface AuthCapabilitiesVO {
  strategy: 'fu-gw-token' | 'self-managed' | string
  supportsLogin: boolean
  supportsRegister: boolean
  requiresAuth: boolean
  availableStrategies: string[]
  captcha: {
    provider: 'altcha' | 'self-hosted-slider' | 'turnstile' | 'off' | string
  }
}

/** 登录请求 */
export interface LoginPayload {
  phone: string
  password: string
  captchaId?: string
  captchaCode?: string
}

/** 账密登录 */
export function login(payload: LoginPayload): Promise<LoginResultVO> {
  return http.post<LoginResultVO>(`${BASE_URL}/login`, payload)
}

/** 刷新 token (rotation). 后端请求体字段为 snake_case */
export function refresh(refreshToken: string): Promise<LoginResultVO> {
  return http.post<LoginResultVO>(`${BASE_URL}/refresh`, { refresh_token: refreshToken })
}

/** 登出 */
export function logout(): Promise<void> {
  return http.post<void>(`${BASE_URL}/logout`)
}

/** 获取验证码挑战 (altcha / slider 由后端 provider 决定) */
export function getCaptcha(): Promise<CaptchaChallengeVO> {
  return http.post<CaptchaChallengeVO>(`${BASE_URL}/captcha`)
}

/** 单独校验验证码 (altcha: code = base64 payload; slider: code = X 偏移) */
export function verifyCaptcha(captchaId: string, captchaCode: string): Promise<{ verified: boolean }> {
  return http.post<{ verified: boolean }>(`${BASE_URL}/captcha/verify`, { captchaId, captchaCode })
}

/** 探测当前鉴权能力 */
export function getCapabilities(): Promise<AuthCapabilitiesVO> {
  return http.get<AuthCapabilitiesVO>(`${BASE_URL}/capabilities`)
}