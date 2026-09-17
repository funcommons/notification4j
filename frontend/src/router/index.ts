import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useEmbedParams } from '@/composables/useEmbedParams'
import i18n from '@/locales'
import { ElMessageBox } from 'element-plus'

// notification4j 路由表
// benefit4j 裁剪残留 (portal / dev / auth / benefit 路由段) 已归档至 legacy/,
// 仅保留消息中心 (/nfy/tenant/**) 嵌入壳路由 (PRD F-EMB: app 壳 + bell 单页壳, 嵌入自鉴权 public).
const routes: RouteRecordRaw[] = [
  {
    path: '/nfy/tenant/app/:page(messages|announcements|channels|subscriptions|deliveries)?',
    component: () => import('@/views/nfy/NfyShell.vue'),
    meta: { public: true, hideInMenu: true },
    children: [
      { path: '', name: 'NfyHome', redirect: '/nfy/tenant/app/messages' },
      { path: 'messages', name: 'NfyMessages', component: () => import('@/views/nfy/MessagesPage.vue') },
      { path: 'announcements', name: 'NfyAnnouncements', component: () => import('@/views/nfy/AnnouncementsPage.vue') },
      { path: 'channels', name: 'NfyChannels', component: () => import('@/views/nfy/ChannelsPage.vue') },
      { path: 'subscriptions', name: 'NfySubscriptions', component: () => import('@/views/nfy/SubscriptionsPage.vue') },
      { path: 'deliveries', name: 'NfyDeliveries', component: () => import('@/views/nfy/DeliveriesPage.vue') }
    ]
  },
  {
    path: '/nfy/tenant/page/bell',
    name: 'NfyBell',
    component: () => import('@/views/nfy/BellPage.vue'),
    meta: { public: true, hideInMenu: true, layout: 'blank' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach(async (to, _from) => {
  const title = to.meta.title as string
  if (title) {
    document.title = `${i18n.global.t(title)} - ${i18n.global.t('app.name')}`
  }

  // 嵌入模式: 解析外观参数 (brand/mode/language), 即时生效不持久化
  const embed = useEmbedParams()
  embed.applyFromRoute(to)

  return true
})

// 全局兜底: 任意路由变化都过一遍 dirty form registry (#2)
router.beforeEach(async (_to, _from) => {
  const dirty = dirtyFormRegistry.findDirty()
  if (dirty) {
    try {
      await ElMessageBox.confirm(
        i18n.global.t('ux.dirty-form.message'),
        i18n.global.t('ux.dirty-form.title'),
        {
          confirmButtonText: i18n.global.t('ux.dirty-form.discard'),
          cancelButtonText: i18n.global.t('ux.dirty-form.cancel'),
          type: 'warning',
        },
      )
      dirty.discard()
      return true
    } catch {
      return false
    }
  }
  return true
})

/**
 * 脏表单全局注册表 (#2 表单未保存提示 — 路由级兜底).
 * useDirtyForm 自动 register/unregister, 此处提供 findDirty 给 router guard 用.
 */
export const dirtyFormRegistry = {
  _forms: new Set<{ isDirty: () => boolean; discard: () => void }>(),

  register(form: { isDirty: () => boolean; discard: () => void }) {
    this._forms.add(form)
  },

  unregister(form: { isDirty: () => boolean; discard: () => void }) {
    this._forms.delete(form)
  },

  findDirty() {
    for (const f of this._forms) {
      if (f.isDirty()) return f
    }
    return null
  },
}

export default router
