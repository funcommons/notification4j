import axios from 'axios'
import type { AxiosResponse } from 'axios'

export interface TokenResult {
  access_token: string
  token_type: string
  expires_in: number
}

interface AuthApiResponse {
  code: number
  message: string
  data: TokenResult
}

const authClient = axios.create({
  baseURL: '/',
  timeout: 10000,
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
})

export async function login(clientId: string, clientSecret: string): Promise<TokenResult> {
  const params = new URLSearchParams()
  params.append('grant_type', 'client_credentials')
  params.append('client_id', clientId)
  params.append('client_secret', clientSecret)

  const response: AxiosResponse<AuthApiResponse> = await authClient.post(
    '/benefit/api/v1/auth/token',
    params,
  )

  const res = response.data
  if (res.code !== 0) {
    throw new Error(res.message || '认证失败')
  }
  return res.data
}
