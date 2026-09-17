import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'
import type { ItemTemplate } from '@/types/benefit'

export type { ItemTemplate }

export const getItemTemplates = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<ItemTemplate[]>>('benefit/api/v1/platform/item-templates', { params })
}

export const createItemTemplate = (data: Partial<ItemTemplate>) => {
  return benefitClient.post<any, ApiResponse<{ id: string }>>('benefit/api/v1/platform/item-templates', data)
}

export const updateItemTemplate = (id: string, data: Partial<ItemTemplate>) => {
  return benefitClient.put<any, ApiResponse<void>>(`benefit/api/v1/platform/item-templates/${id}`, data)
}

export const deleteItemTemplate = (id: string) => {
  return benefitClient.delete<any, ApiResponse<void>>(`benefit/api/v1/platform/item-templates/${id}`)
}