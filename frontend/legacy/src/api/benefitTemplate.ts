import { benefitClient } from './benefitClient'
import type { ApiResponse } from './benefitClient'
import type { GlobalTemplate, TemplateItemRef } from '@/types/benefit'

export type { GlobalTemplate, TemplateItemRef }

// Platform CRUD
export const getTemplates = (params?: Record<string, any>) => {
  return benefitClient.get<any, ApiResponse<GlobalTemplate[]>>('benefit/api/v1/platform/global-templates', { params })
}

export const createTemplate = (data: GlobalTemplate) => {
  return benefitClient.post<any, ApiResponse<string>>('benefit/api/v1/platform/global-templates', data)
}

export const updateTemplate = (id: string, data: GlobalTemplate) => {
  return benefitClient.put<any, ApiResponse<void>>(`benefit/api/v1/platform/global-templates/${id}`, data)
}

export const deleteTemplate = (id: string) => {
  return benefitClient.delete<any, ApiResponse<void>>(`benefit/api/v1/platform/global-templates/${id}`)
}

// Tenant — 仅可见平台模板
export const getTenantTemplates = () => {
  return benefitClient.get<any, ApiResponse<GlobalTemplate[]>>('benefit/api/v1/tenant/benefit-templates')
}