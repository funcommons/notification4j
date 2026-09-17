import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface AppEntity {
  id?: string
  name?: string
  app_secret?: string
  description?: string
  status?: string
  ext?: any
  subscription_count?: number
  created_at?: string
  updated_at?: string
}

export const getApps = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<AppEntity[]>>('benefit/api/v1/platform/applications', { params })
}

export const createApp = (data: any) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/platform/applications', data)
}

export const updateApp = (id: string, data: any) => {
  return benefitClient.put<any, ApiResponse<void>>(`benefit/api/v1/platform/applications/${id}`, data)
}

export const deleteApp = (id: string) => {
  return benefitClient.delete<any, ApiResponse<void>>(`benefit/api/v1/platform/applications/${id}`)
}

export const resetAppSecret = (id: string) => {
  return benefitClient.post<any, ApiResponse<{ id: string; app_secret: string }>>(
    `benefit/api/v1/platform/applications/${id}/reset-secret`
  )
}

export const revealAppSecret = (id: string) => {
  return benefitClient.get<any, ApiResponse<{ id: string; app_secret: string }>>(
    `benefit/api/v1/platform/applications/${id}/secret`
  )
}