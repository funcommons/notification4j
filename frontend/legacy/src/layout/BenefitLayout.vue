<template>
  <div class="benefit-layout">
    <FcSidebar :collapse="collapsed" @update:collapse="collapsed = $event">
      <FcSidebarNav :items="navItems" :active-path="route.path" :collapse="collapsed" @select="handleSelect" />
    </FcSidebar>

    <FcMain class="main-content">
      <div class="page-container">
        <router-view />
      </div>
    </FcMain>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Odometer, Setting, Goods, Collection, Tickets, Document, PriceTag, List, Tools } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  FcMain, FcSidebar, FcSidebarNav,
  useSidebarNavItems,
} from '@/components/sdk'
import { useKeyboardShortcut, openCommandPalette } from '@/composables'
import { authBus } from '@/utils/authBus'

const router = useRouter()
const route = useRoute()

const collapsed = ref(false)

// 跨标签页登录态同步: A 页被踢 → B 页跟着跳走
let offAuthBus: (() => void) | null = null
const onAuthExpired = (code: number) => {
  // 清本地登录态 (拦截器已在发起方清理过, 这里只处理被广播唤醒的标签页)
  sessionStorage.removeItem('benefit4j:access_token')
  sessionStorage.removeItem('benefit4j:expires_at')
  sessionStorage.removeItem('benefit4j:tenant_id')

  if (code === 10205) {
    ElMessage.warning('账号已在别处登录, 请重新登录')
  }
  const isPlatform = route.path.startsWith('/benefit/platform/')
  const loginPath = isPlatform ? '/benefit/app/platform/login' : '/benefit/app/tenant/login'
  if (route.path !== loginPath) {
    window.location.href = loginPath
  }
}
onMounted(() => {
  offAuthBus = authBus.on((ev) => {
    if (ev.type === 'auth-expired') onAuthExpired(ev.code)
  })
})
onBeforeUnmount(() => {
  offAuthBus?.()
})

const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const navItems = useSidebarNavItems({
  iconResolver: (name: string) => {
    const icons: Record<string, any> = { Odometer, Setting, Goods, Collection, Tickets, Document, PriceTag, List, Tools }
    return icons[name] || Odometer
  },
  topLevels: isPlatform.value
    ? [
        { routeNames: ['BenefitPlatformDashboard', 'BenefitTenants', 'BenefitPlatformItems', 'BenefitPlatformSets'] },
      ]
    : [
        { routeNames: ['BenefitTenantDashboard', 'BenefitItems', 'BenefitSets', 'BenefitTenantTemplates', 'BenefitSubscriptions', 'BenefitConsumptions'] },
      ],
  groups: isPlatform.value
    ? [
        { id: 'platform-templates', labelKey: 'router.benefit-platform-templates', iconName: 'Document', routeNames: ['BenefitPlatformTemplatesSet', 'BenefitPlatformTemplatesItem'] },
        { id: 'platform-records', labelKey: 'router.benefit-platform-records', iconName: 'List', routeNames: ['BenefitPlatformSubscriptions', 'BenefitPlatformConsumptions'] },
        { id: 'dev-tools', labelKey: 'router.dev-tools', iconName: 'Tools', routeNames: ['BenefitPlatformEmbedDocs', 'BenefitPlatformEmbedTest'] },
      ]
    : [],
})

const handleSelect = (path: string) => {
  router.push(path)
}

// 全局快捷键: Cmd/Ctrl+K → 命令面板 (#9)
useKeyboardShortcut('mod+k', () => openCommandPalette())
</script>

<style scoped lang="scss">
.benefit-layout {
  display: flex;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}
// SDK 的 .fc-sidebar 默认 top: 56px / height: calc(100vh - 56px), 假定有顶部 header
// 这里 header 已移除, 侧栏改为贴顶铺满
:deep(.fc-sidebar) {
  top: 0;
  height: 100vh;
}
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.page-container {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  background: var(--app-bg-page);
}
</style>