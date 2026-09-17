<script setup lang="ts">
// 消息中心完整壳（/nfy/tenant/app/*，PRD F-EMB：侧栏五页，V1.2 增投递）。
// 嵌入模式自持 token（postMessage 握手），不依赖 benefit 登录态。
import { computed } from 'vue'
import { provideNfy } from '@/api/nfy/nfyContext'

const props = defineProps<{ baseUrl?: string; origins?: string }>()

const ctx = provideNfy({
  baseUrl: props.baseUrl ?? import.meta.env.VITE_NFY_BASE_URL ?? '/nfy/api/v1',
  allowedOrigins: (props.origins ?? import.meta.env.VITE_NFY_PARENT_ORIGINS ?? 'http://localhost:5173,http://localhost:3000')
    .split(',')
    .map((s: string) => s.trim()),
})

// F-2：占位区分「等待握手」「会话失效重连」——无效/过期 token 触发数据面
// 鉴权失败（401/信封 102xx）后回 waiting 重握手，呈现明确连接态而非误导空态
const placeholder = computed(() => {
  const waiting = ctx.handshake.status.value === 'waiting'
  if (ctx.authError.value) return waiting ? '会话已失效，正在重新连接…' : '会话已失效，请刷新'
  return waiting ? '正在连接…' : '会话已过期，请刷新'
})
</script>

<template>
  <div class="nfy-shell">
    <template v-if="ctx.ready.value">
      <aside class="nfy-side">
        <div class="nfy-brand">消息中心</div>
        <nav>
          <RouterLink to="/nfy/tenant/app/messages">消息</RouterLink>
          <RouterLink to="/nfy/tenant/app/announcements">公告</RouterLink>
          <RouterLink to="/nfy/tenant/app/channels">渠道</RouterLink>
          <RouterLink to="/nfy/tenant/app/subscriptions">订阅</RouterLink>
          <RouterLink to="/nfy/tenant/app/deliveries">投递</RouterLink>
        </nav>
      </aside>
      <main class="nfy-main">
        <RouterView />
      </main>
    </template>
    <div v-else class="nfy-placeholder">
      <!-- 契约：token 缺失/过期 → 静默占位，不打断父页；握手自动重试 -->
      <span>{{ placeholder }}</span>
    </div>
  </div>
</template>

<style scoped>
.nfy-shell { display: flex; height: 100vh; font-size: 14px; }
.nfy-side { width: 168px; border-right: 1px solid var(--el-border-color); padding: 16px 12px; }
.nfy-brand { font-weight: 600; margin-bottom: 16px; }
.nfy-side nav { display: flex; flex-direction: column; gap: 4px; }
.nfy-side nav a { padding: 8px 10px; border-radius: 6px; color: inherit; text-decoration: none; }
.nfy-side nav a.router-link-active { background: var(--el-fill-color-light); font-weight: 500; }
.nfy-main { flex: 1; overflow: auto; padding: 16px 20px; }
.nfy-placeholder { margin: auto; color: var(--el-text-color-secondary); }
</style>
