import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface BenefitSetItemRef {
  item_id: string
  quota?: number
  refresh_cycle?: number
  refresh_cycle_unit?: string
}

export interface BenefitSet {
  id?: string
  tenant_id?: string
  name?: string
  duration?: number
  duration_unit?: string
  priority?: number
  quota?: number
  refresh_cycle?: number
  refresh_cycle_unit?: string
  timing_mode?: string
  quota_unit?: string
  status?: string
  ext?: any
  items?: BenefitSetItemRef[]
  created_at?: string
  updated_at?: string
}

// Tenant — 自身名下权益包 CRUD
export const getBenefitSets = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<BenefitSet[]>>('benefit/api/v1/tenant/benefit-sets', { params })
}

export const createBenefitSet = (data: any) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/tenant/benefit-sets', data)
}

export const updateBenefitSet = (id: string, data: any) => {
  return benefitClient.put<any, ApiResponse<void>>(`benefit/api/v1/tenant/benefit-sets/${id}`, data)
}

export const deleteBenefitSet = (id: string) => {
  return benefitClient.delete<any, ApiResponse<void>>(`benefit/api/v1/tenant/benefit-sets/${id}`)
}

// Platform — 跨租户权益包聚合列表 (与 tenant 共用 Sets.vue), 多条件分页只读
export interface PlatformBenefitSetQuery {
  tenant_id?: string
  status?: string
  keyword?: string
  priority_min?: number
  priority_max?: number
  created_at_start?: string
  created_at_end?: string
  page?: number
  size?: number
}

export interface PlatformBenefitSet extends BenefitSet {
  tenant_id: string
  tenant_name?: string
  subscribe_count?: number
}

export interface PlatformBenefitSetPage {
  list: PlatformBenefitSet[]
  total: number
  page: number
  size: number
}

export const getPlatformBenefitSets = (params?: PlatformBenefitSetQuery) => {
  return benefitClient.get<any, ApiResponse<PlatformBenefitSetPage>>('benefit/api/v1/platform/benefit-sets', { params })
}