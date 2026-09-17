/**
 * useSidebarNavItems — 项目侧 nav 工厂 (基于 SDK 底座).
 *
 * /dev-only 改造后, 侧栏只剩一个 "开发者" 入口.
 * benefit4j 入口由 BenefitLayout 自行管理, 不在此处.
 */
import { computed, type ComputedRef } from 'vue'
import type { Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { Tools } from '@element-plus/icons-vue'
import type { NavItem } from '@/components/sdk'

/** 默认展开的 sub-menu id 列表 (无 sub-menu 时为空数组) */
export const NAV_DEFAULT_OPENEDS: string[] = []

export function useSidebarNavItems(): ComputedRef<NavItem[]> {
  const { t } = useI18n()
  return computed<NavItem[]>(() => [{
    index: '/dev',
    label: t('router.dev-index'),
    icon: Tools as unknown as Component,
  }])
}
