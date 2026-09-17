/**
 * notification4j 四域 API（接口设计文档 §5）：
 * runtime/messages / runtime/announcements / runtime/channels / runtime/subscriptions
 * + admin/deliveries（§5.9.2 管理面，V1.2 人工重投）。
 * 均为消息中心（嵌入 iframe）端点，X-User-Id 由 client 统一注入。
 */
import type { NfyClient } from './client'

// ==== API-MSG 消息与站内信（§5.2~§5.4）====
export interface UnreadCount {
  unread_count: number
  unconfirmed_count: number
  total: number
}

export interface MessageItem {
  message_id: string
  title: string
  type_code: string
  level: string
  read_status: string
  created_at: number | string | null  // Long→String 契约：雪花毫秒以字符串到达（utils/fwkTime）
}

export interface MessageListResult {
  list: MessageItem[]
  next_cursor: string | null
  has_more: boolean
}

export const createMessageApi = (c: NfyClient) => ({
  unreadCount: () => c.get<UnreadCount>('/runtime/messages/unread-count'),
  list: (q: { type_code?: string; level?: string; read_status?: string; cursor?: string; limit?: number }) =>
    c.get<MessageListResult>('/runtime/messages', q as Record<string, unknown>),
  markRead: (body: { message_ids?: string[]; all?: boolean }) =>
    c.post<{ read_count: number }>('/runtime/messages/read', body),
})

// ==== API-ANN 公告（§5.6）====
export interface AnnouncementItem {
  announcement_id: string
  title: string
  scope: string
  level: string
  content: string
  link_url: string
  need_confirm: number
  published_at: number | string | null  // Long→String 契约（utils/fwkTime）
  my_status: 'NONE' | 'READ' | 'CONFIRMED'
}

export interface AnnouncementListResult {
  list: AnnouncementItem[]
  next_cursor: string | null
  has_more: boolean
}

export const createAnnouncementApi = (c: NfyClient) => ({
  list: (q: { cursor?: string; limit?: number }) =>
    c.get<AnnouncementListResult>('/runtime/announcements', q as Record<string, unknown>),
  markRead: (id: string) => c.post<{ read: boolean }>(`/runtime/announcements/${id}/read`),
  confirm: (id: string) => c.post<{ confirmed: boolean }>(`/runtime/announcements/${id}/confirm`),
})

// ==== API-CHN 渠道（§5.7/§5.9.1）====
export interface ChannelItem {
  channel_id: string
  channel_type: string
  name: string
  target: string
  status: string
  fail_count: number
  last_verify_at: number | string | null  // Long→String 契约（utils/fwkTime）
}

export const createChannelApi = (c: NfyClient) => ({
  list: () => c.get<{ list: ChannelItem[] }>('/runtime/channels'),
  register: (body: { channel_type: string; name: string; target: string; secret?: string; keyword?: string }) =>
    c.post<{ channel_id: string; status: string; verify_tip: string }>('/runtime/channels', body),
  verify: (id: string) => c.post<{ status: string }>(`/runtime/channels/${id}/verify`),
  patch: (id: string, body: { name?: string; status?: string }) =>
    c.patch<{ status: string }>(`/runtime/channels/${id}`, body),
  remove: (id: string) => c.del<{ channel_id: string }>(`/runtime/channels/${id}`),
})

// ==== API-SUB 订阅矩阵（§5.8）====
export interface SubscriptionType {
  type_code: string
  name: string
  description: string
  mandatory: number
  default_channels: string[]
}

export interface SubscriptionMatrix {
  types: SubscriptionType[]
  available_channels: { channel_id: string; channel_type?: string; name?: string }[]
  items: { type_code: string; channel_ids: string[] }[]
}

export const createSubscriptionApi = (c: NfyClient) => ({
  get: () => c.get<SubscriptionMatrix>('/runtime/subscriptions'),
  save: (items: { type_code: string; channel_ids: string[] }[]) =>
    c.put<{ saved_count: number }>('/runtime/subscriptions', { items }),
})

// ==== API-DLV 投递记录与人工重投（§5.9.2 管理面，V1.2）====
export interface DeliveryItem {
  delivery_id: string
  source_type: string
  source_id: string
  userid: string
  channel_id: string
  channel_type: string
  /** 快照已后端脱敏，前端只读展示 */
  target: string
  title: string
  status: string
  retry_count: number
  next_retry_at: number | string | null  // Long→String 契约（utils/fwkTime）
  error_message: string | null
  sent_at: number | string | null  // Long→String 契约（utils/fwkTime）
  created_at: number | string | null  // Long→String 契约：雪花毫秒以字符串到达（utils/fwkTime）
}

export interface DeliveryListResult {
  list: DeliveryItem[]
  total: number
}

/** DLV-001 查询参数（Offset 分页；created_* 为 epoch ms） */
export interface DeliveryListQuery {
  biz_no?: string
  userid?: string
  channel_type?: string
  status?: string
  created_after?: number
  created_before?: number
  offset?: number
  limit?: number
}

export const createDeliveryApi = (c: NfyClient) => ({
  list: (q: DeliveryListQuery) =>
    c.get<DeliveryListResult>('/admin/deliveries', q as Record<string, unknown>),
  /** DLV-002 人工重投：仅 DEAD → {status:"PENDING"}；非 DEAD → 10402 */
  retry: (deliveryId: string) =>
    c.post<{ delivery_id: string; status: string }>(`/admin/deliveries/${deliveryId}/retry`),
})
