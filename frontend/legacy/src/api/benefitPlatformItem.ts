import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface PlatformItem {
  id: string
  tenant_id: string
  tenant_name?: string
  name: string
  icon: string
  description: string
  default_deduction: number
  status: 'ACTIVE' | 'INACTIVE' | string
  quota: number
  used: number
  usage_pct: number
  created_at?: string
  updated_at: string
}

export interface PlatformItemPage<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface PlatformItemQuery {
  tenant_id?: string
  status?: string
  keyword?: string
  created_at_start?: string
  created_at_end?: string
  page?: number
  size?: number
}

export const getPlatformItems = (params?: PlatformItemQuery) => {
  return benefitClient.get<any, ApiResponse<PlatformItemPage<PlatformItem>>>(
    'benefit/api/v1/platform/items',
    { params }
  )
}