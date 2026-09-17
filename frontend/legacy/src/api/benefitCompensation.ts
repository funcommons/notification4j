import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface PostCompensationRequest {
  subscribe_id: string
  subs_item_id: string
  item_id: string
  adjust_num: number
  adjust_type?: 'ADD' | 'REDUCE'
  reason?: string
  operator?: string
  // ADD-only: 新桶字段 (V1.2.0 多源桶)
  source_type?: string
  priority?: number
  expires_at?: string | null
}

export interface CompensationResponse {
  compensation_id?: string
  subscribe_id?: string
  subs_item_id?: string
  item_id?: string
  adjust_num?: number
  adjust_type?: string
  source_type?: string
  bucket_priority?: number
  expires_at?: string | null
  quota_limit?: number
  reason?: string
  operator?: string
  created_at?: string
}

// Tenant
export const postCompensation = (data: PostCompensationRequest) => {
  return benefitClient.post<any, ApiResponse<CompensationResponse>>(
    'benefit/api/v1/tenant/compensations',
    data,
  )
}

// Platform (tenant_id 在 query)
export const platformPostCompensation = (data: PostCompensationRequest, tenantId?: number | string) => {
  return benefitClient.post<any, ApiResponse<CompensationResponse>>(
    'benefit/api/v1/platform/compensations',
    data,
    { params: tenantId !== undefined ? { tenant_id: tenantId } : undefined },
  )
}