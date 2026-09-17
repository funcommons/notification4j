import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

export interface SubscriptionBucket {
  id?: string
  subscribe_id?: string
  item_id?: string
  item_name?: string
  quota_limit?: number
  period_consumed?: number
  frozen_consumed?: number
  source_type?: string
  bucket_priority?: number
  expires_at?: string | null
  status?: string
  next_refresh_time?: string
}

export interface Subscription {
  id?: string
  tenant_id?: string
  app_name?: string
  userid?: string
  set_id?: string
  set_name?: string
  status?: string
  external_order_id?: string
  date_begin?: string
  date_end?: string
  quota_limit?: number
  period_consumed?: number
  frozen_consumed?: number
  created_at?: string
  updated_at?: string
}

export interface SubscriptionQuery {
  userid?: string
  set_id?: string
  status?: string
  external_order_id?: string
  keyword?: string
  date_begin_start?: string
  date_begin_end?: string
  created_at_start?: string
  created_at_end?: string
  page?: number
  size?: number
  tenant_id?: number | string
}

// Tenant — 当前租户名下订阅列表
export const listSubscriptions = (params?: SubscriptionQuery) => {
  return benefitClient.get<any, ApiResponse<{ list: Subscription[]; total: number; page: number; size: number }>>(
    'benefit/api/v1/tenant/subscriptions',
    { params },
  )
}

// Platform — 跨租户订阅列表 (tenant_id 可选)
export const platformListSubscriptions = (params?: SubscriptionQuery) => {
  return benefitClient.get<any, ApiResponse<{ list: Subscription[]; total: number; page: number; size: number }>>(
    'benefit/api/v1/platform/subscriptions',
    { params },
  )
}

// 查询订阅明细桶列表 (V1.2.0 多源桶)
export const listSubscriptionItems = (subscribeId: string, params?: { item_id?: string }) => {
  return benefitClient.get<any, ApiResponse<{ list: SubscriptionBucket[] }>>(
    `benefit/api/v1/tenant/subscriptions/${subscribeId}/items`,
    { params },
  )
}

export const platformListSubscriptionItems = (subscribeId: string, params?: { item_id?: string; tenant_id?: number | string }) => {
  return benefitClient.get<any, ApiResponse<{ list: SubscriptionBucket[] }>>(
    `benefit/api/v1/platform/subscriptions/${subscribeId}/items`,
    { params },
  )
}

// ———————————— 历史 runtime 端点保留 ————————————
export const subscribe = (data: { userid: string; set_id: string; external_order_id: string }) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/tenant/subscriptions', data)
}

export const unsubscribe = (data: { subscribe_id: string; external_order_id: string; reason?: string }) => {
  return benefitClient.post<any, ApiResponse<void>>('benefit/api/v1/runtime/subscriptions/cancel', data)
}

export const migrate = (data: {
  userid: string
  from_subscribe_id: string
  to_set_id: string
  external_migrate_id: string
  migrate_type: 'UPGRADE' | 'DOWNGRADE'
  reason?: string
}) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/runtime/migrations', data)
}

export const refund = (data: { consume_id: string; external_refund_id: string; refund_num?: number }) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/runtime/refunds', data)
}