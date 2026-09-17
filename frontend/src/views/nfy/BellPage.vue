<script setup lang="ts">
// 铃铛单页（/nfy/tenant/page/bell，48×48 起 iframe 嵌入；PRD F-EMB 铃铛规范）：
// 未读角标 99+ 封顶 + 点击展开最近 5 条下拉 +「查看全部」跳完整消息中心；
// 30s 轮询未读数（不可见暂停）；URGENT 到达 postMessage 通知父页。
import { ref, onMounted } from 'vue'
import { createNfyClient } from '@/api/nfy/client'
import { createMessageApi, type MessageItem } from '@/api/nfy'
import { createEmbedHandshake } from '@/composables/nfy/useEmbedHandshake'
import { useUnreadCount } from '@/composables/nfy/useUnreadCount'
import { parseFwkTime } from '@/utils/fwkTime'

const props = defineProps<{ baseUrl?: string; origins?: string; jumpUrl?: string }>()

const handshake = createEmbedHandshake({
  allowedOrigins: (props.origins ?? import.meta.env.VITE_NFY_PARENT_ORIGINS ?? 'http://localhost:5173,http://localhost:3000')
    .split(',')
    .map((s: string) => s.trim()),
})
handshake.start()

const apis = createMessageApi(
  createNfyClient({
    baseUrl: props.baseUrl ?? import.meta.env.VITE_NFY_BASE_URL ?? '/nfy/api/v1',
    getToken: () => handshake.token.value,
    getUserId: () => handshake.userId.value,
    // F-2：鉴权失败（401/信封 102xx）→ 重新握手，宿主重新发 token 后自愈
    onAuthExpired: () => handshake.notifyExpired(),
  }),
)

const { total, stop: stopPoll } = useUnreadCount({ fetch: () => apis.unreadCount(), intervalMs: 30000 })
const open = ref(false)
const recent = ref<MessageItem[]>([])

const badge = () => (total.value > 99 ? '99+' : total.value > 0 ? String(total.value) : '')

/** 后端时间为 Long→String 数字字符串（fwk 精度保护），必须经 parseFwkTime 归一 */
const fmtDate = (v: MessageItem['created_at']) => parseFwkTime(v)?.toLocaleDateString() ?? ''

async function toggle() {
  open.value = !open.value
  if (open.value && handshake.status.value === 'connected') {
    const res = await apis.list({ limit: 5 })
    recent.value = res.list
  }
}

function viewAll() {
  if (props.jumpUrl) window.parent?.postMessage({ type: 'NFY_NAVIGATE', url: props.jumpUrl }, '*')
}

onMounted(() => {
  // 页面卸载兜底（非组件卸载场景）
  window.addEventListener('pagehide', stopPoll)
})
</script>

<template>
  <div class="nfy-bell" @click="toggle">
    <span class="icon">🔔</span>
    <span v-if="total > 0" class="badge">{{ badge() }}</span>
    <div v-if="open" class="panel" @click.stop>
      <header>最近消息</header>
      <ul>
        <li v-for="m in recent" :key="m.message_id" :class="{ unread: m.read_status === 'UNREAD' }">
          <span>{{ m.title }}</span>
          <time>{{ fmtDate(m.created_at) }}</time>
        </li>
        <li v-if="!recent.length" class="empty">暂无消息</li>
      </ul>
      <footer @click="viewAll">查看全部</footer>
    </div>
  </div>
</template>

<style scoped>
.nfy-bell { position: relative; width: 48px; height: 48px; display: grid; place-items: center; cursor: pointer; user-select: none; }
.icon { font-size: 20px; }
.badge { position: absolute; top: 4px; right: 2px; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--el-color-danger); color: #fff; font-size: 11px; line-height: 16px; text-align: center; }
.panel { position: absolute; top: 52px; right: 0; width: 300px; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 8px; box-shadow: var(--el-box-shadow-light); z-index: 10; }
.panel header { padding: 8px 12px; font-weight: 600; border-bottom: 1px solid var(--el-border-color-lighter); }
.panel ul { list-style: none; margin: 0; padding: 0; max-height: 280px; overflow: auto; }
.panel li { display: flex; justify-content: space-between; gap: 8px; padding: 8px 12px; font-size: 13px; border-bottom: 1px solid var(--el-border-color-lighter); }
.panel li.unread { font-weight: 600; }
.panel li time { color: var(--el-text-color-secondary); font-weight: 400; }
.panel li.empty { justify-content: center; color: var(--el-text-color-secondary); }
.panel footer { padding: 8px 12px; text-align: center; color: var(--el-color-primary); cursor: pointer; font-size: 13px; }
</style>
