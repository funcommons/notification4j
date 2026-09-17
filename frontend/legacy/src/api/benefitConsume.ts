import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface ConsumeRecord {
  consume_id?: string
  tenant_id?: string
  userid?: string
  subs_item_id?: string
  item_id?: string
  item_name?: string
  external_order_id?: string
  consume_num?: number
  status?: string
  consume_time?: string
  expire_time?: string
  refundable?: boolean
  ext?: any
  created_at?: string
}

export interface ConsumeQuery {
  userid?: string
  subs_item_id?: string
  item_id?: string
  status?: string
  external_order_id?: string
  keyword?: string
  consume_num_min?: number
  consume_num_max?: number
  consume_time_start?: string
  consume_time_end?: string
  page?: number
  size?: number
  tenant_id?: number | string
}

// 直接扣减请求 (V1.2.0 多源桶 + partialAllowed)
export interface PostConsumeDirectRequest {
  userid: string
  item_id: string
  external_order_id: string
  consume_num: number
  partial_allowed?: boolean
}

export interface ConsumeDirectResponse {
  consume_num?: number
  total_available?: number
  requested?: number
  consume_ids?: string[]
  source_breakdown?: Array<{ subs_item_id: string; consumed: number; source_type?: string }>
}

// Tenant — 当前租户名下扣减记录
export const listConsumes = (params?: ConsumeQuery) => {
  return benefitClient.get<any, ApiResponse<{ list: ConsumeRecord[]; total: number; page: number; size: number }>>(
    'benefit/api/v1/tenant/consumes',
    { params },
  )
}

// Platform — 跨租户扣减记录 (tenant_id 可选)
export const platformListConsumes = (params?: ConsumeQuery) => {
  return benefitClient.get<any, ApiResponse<{ list: ConsumeRecord[]; total: number; page: number; size: number }>>(
    'benefit/api/v1/platform/consumes',
    { params },
  )
}

export const refundConsume = (consumeId: string, data: { reason?: string; operator?: string }) => {
  return benefitClient.post<any, ApiResponse<void>>(
    `benefit/api/v1/tenant/consumes/${consumeId}/refund`,
    data,
  )
}

export const platformRefundConsume = (consumeId: string, data: { reason?: string; operator?: string }, tenantId?: number | string) => {
  return benefitClient.post<any, ApiResponse<void>>(
    `benefit/api/v1/platform/consumes/${consumeId}/refund`,
    data,
    { params: tenantId !== undefined ? { tenant_id: tenantId } : undefined },
  )
}

// 直接扣减 — 多源桶排空 (V1.2.0)
export const postConsumeDirect = (data: PostConsumeDirectRequest) => {
  return benefitClient.post<any, ApiResponse<ConsumeDirectResponse>>(
    'benefit/api/v1/runtime/consumes/direct',
    data,
  )
}

export const platformPostConsumeDirect = (data: PostConsumeDirectRequest, tenantId?: number | string) => {
  return benefitClient.post<any, ApiResponse<ConsumeDirectResponse>>(
    'benefit/api/v1/platform/consumes/direct',
    data,
    { params: tenantId !== undefined ? { tenant_id: tenantId } : undefined },
  )
}