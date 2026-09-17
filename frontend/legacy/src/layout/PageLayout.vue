<template>
  <div class="page-layout">
    <!-- FcMain 渲染 .fc-main — 全局 .fc-main .fc-section 响应式 padding、
         el-row/el-col 间距重置、主题背景/文字色都挂在它下面。
         单页模式若漏掉它, FcSection 会丢内边距 (padding: inherit 依赖此祖先)。 -->
    <FcMain class="page-main">
      <div class="page-container">
        <router-view />
      </div>
    </FcMain>
  </div>
</template>

<script setup lang="ts">
/**
 * PageLayout - 嵌入模式单页 layout。
 *
 * 与 BenefitLayout 的区别:
 *  - 无 FcSidebar (侧栏菜单)
 *  - 无 Cmd/Ctrl+K 命令面板
 *  - 挂载 useEmbedToken (postMessage 握手 / URL token 兼容)
 *  - 容器 min-height: 100vh + width: 100%, iframe 内容自适应增高
 *
 * 与 BenefitLayout 的一致点:
 *  - 内容用 FcMain (.fc-main) 包裹, 保证 FcSection padding / el-row 重置 /
 *    主题背景文字色与 app 模式完全一致, 避免单页样式丢失。
 *
 * 路由: /benefit/platform/page/* 和 /benefit/tenant/page/*
 */
import { FcMain } from '@/components/sdk'
import { useEmbedToken } from '@/composables/useEmbedToken'

// 推荐级: postMessage 握手 (token 不进 URL)
// 基础级: URL 带 access_token 时, 此 composable 静默跳过 (status='basic')
const { status: embedStatus } = useEmbedToken()

// embedStatus 可用于后续 UI 反馈 (如握手等待态), 当前不渲染
void embedStatus
</script>

<style scoped lang="scss">
.page-layout {
  min-height: 100vh;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-container {
  flex: 1;
  padding: 24px;
}
</style>
