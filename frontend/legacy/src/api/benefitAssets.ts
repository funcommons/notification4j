import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'

/** 资产定义(注册中心) */
export interface AssetDefinition {
  code?: string
  name?: string
  asset_type?: string
  precision?: number
  can_recharge?: boolean
  can_withdraw?: boolean
  can_pay?: boolean
  can_transfer?: boolean
  can_exchange?: boolean
  can_credit?: boolean
  issue_mode?: string
  expire_policy?: Record<string, any>
  limit_policy?: Record<string, any>
  description?: string
  status?: string
  created_at?: string
  updated_at?: string
}

/** 资产账户 */
export interface AssetAccount {
  id?: string
  tenant_id?: string
  owner_type?: string
  owner_id?: string
  asset_code?: string
  account_type?: string
  balance?: string
  credit_limit?: string
  frozen?: string
  status?: string
  created_at?: string
}

/** 账户流水腿 */
export interface AssetPosting {
  id?: string
  tx_id?: string
  tx_type?: string
  ext_order_id?: string
  leg_seq?: number
  src_account_id?: string
  dst_account_id?: string
  asset_code?: string
  amount?: string
  direction?: string
  balance_after?: string
  status?: string
  created_at?: string
}

// ---------- 资产注册中心(平台运营面,APP token) ----------

export const getAssets = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<AssetDefinition[]>>('benefit/api/v1/platform/assets', { params })
}

export const createAsset = (data: Partial<AssetDefinition>) => {
  return benefitClient.post<any, ApiResponse<AssetDefinition>>('benefit/api/v1/platform/assets', data)
}

export const patchAsset = (code: string, data: Partial<AssetDefinition>) => {
  return benefitClient.patch<any, ApiResponse<AssetDefinition>>(`benefit/api/v1/platform/assets/${code}`, data)
}

export const suspendAsset = (code: string) => {
  return benefitClient.post<any, ApiResponse<void>>(`benefit/api/v1/platform/assets/${code}/suspend`)
}

export const resumeAsset = (code: string) => {
  return benefitClient.post<any, ApiResponse<void>>(`benefit/api/v1/platform/assets/${code}/resume`)
}

// ---------- 账户 / 流水(平台运营视角,跨 app;tenant_id 可选收窄) ----------

export const getAssetAccounts = (params: { owner_type: string; owner_id: string; asset_code?: string; tenant_id?: string }) => {
  return benefitClient.get<any, ApiResponse<AssetAccount[]>>('benefit/api/v1/platform/assets/accounts', { params })
}

export const getAssetPostings = (params: { account_ref: string; asset_code: string; tenant_id?: string; page?: number; size?: number }) => {
  return benefitClient.get<any, ApiResponse<AssetPosting[]>>('benefit/api/v1/platform/assets/postings', { params })
}
