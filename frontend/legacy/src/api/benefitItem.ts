import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface BenefitItem {
  id?: string
  tenant_id?: string
  name?: string
  icon?: string
  description?: string
  default_deduction?: number
  status?: string
  ext?: any
  created_at?: string
  updated_at?: string
}

export const getBenefitItems = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<BenefitItem[]>>('benefit/api/v1/tenant/benefit-items', { params })
}

export const createBenefitItem = (data: any) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/tenant/benefit-items', data)
}

export const updateBenefitItem = (id: string, data: any) => {
  return benefitClient.put<any, ApiResponse<void>>(`benefit/api/v1/tenant/benefit-items/${id}`, data)
}

export const deleteBenefitItem = (id: string) => {
  return benefitClient.delete<any, ApiResponse<void>>(`benefit/api/v1/tenant/benefit-items/${id}`)
}
