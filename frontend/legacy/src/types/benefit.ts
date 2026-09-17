/**
 * 模板层类型 — 与后端 ubmp_benefit_tmpl_* 三表字段一一对应
 * 详见 documents/UBM_Database_Design.md §1.4
 */

export type TemplateStatus = 'ACTIVE' | 'INACTIVE'

/** 模板层权益项定义 — 对应 ubmp_benefit_tmpl_item */
export interface ItemTemplate {
  id?: string
  name?: string
  icon?: string
  description?: string
  default_deduction?: number
  status?: TemplateStatus | string
  created_at?: string
  updated_at?: string
}

/** 模板层关联明细 — 对应 ubmp_benefit_tmpl_ref */
export interface TemplateItemRef {
  item_id: string
  quota?: number
  refresh_cycle?: number
  refresh_cycle_unit?: string
}

/** 模板层产品包装配方 — 对应 ubmp_benefit_tmpl_set */
export interface GlobalTemplate {
  id?: string
  name: string
  duration?: number
  duration_unit: string
  priority?: number
  quota?: number
  refresh_cycle?: number
  refresh_cycle_unit?: string
  refs?: TemplateItemRef[]
  status?: TemplateStatus | string
  created_at?: string
  updated_at?: string
}

/** 周期单位枚举 — day/week/month/year */
export const CYCLE_UNITS = ['day', 'week', 'month', 'year'] as const
export type CycleUnit = (typeof CYCLE_UNITS)[number]