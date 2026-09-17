<script setup lang="ts">
import { FcButton } from '@/components/sdk/form'
// 消息页（API-MSG-004/006/007；PRD：未读加粗+等级色标+已读操作，Cursor 加载更多）
import { ref, onMounted } from 'vue'
import { useNfy } from '@/api/nfy/nfyContext'
import { parseFwkTime } from '@/utils/fwkTime'
import type { MessageItem } from '@/api/nfy'

const { apis, authError } = useNfy()
const list = ref<MessageItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const readFilter = ref<'' | 'UNREAD' | 'READ'>('')
const unread = ref(0)

/** 后端时间为 Long→String 数字字符串（fwk 精度保护），必须经 parseFwkTime 归一 */
const fmtTime = (v: MessageItem['created_at']) => parseFwkTime(v)?.toLocaleString() ?? ''

async function load(reset = false) {
  loading.value = true
  try {
    const res = await apis.messages.list({
      read_status: readFilter.value || undefined,
      cursor: reset ? undefined : cursor.value ?? undefined,
      limit: 20,
    })
    list.value = reset ? res.list : [...list.value, ...res.list]
    cursor.value = res.next_cursor
    hasMore.value = res.has_more
  } finally {
    loading.value = false
  }
}

async function refreshUnread() {
  unread.value = (await apis.messages.unreadCount()).unread_count
}

async function markRead(item: MessageItem) {
  await apis.messages.markRead({ message_ids: [item.message_id] })
  item.read_status = 'READ'
  await refreshUnread()
}

onMounted(() => {
  void load(true)
  void refreshUnread()
})
</script>

<template>
  <section>
    <header class="bar">
      <h2>消息 <el-badge :value="unread" :hidden="!unread" /></h2>
      <el-radio-group v-model="readFilter" size="small" @change="() => load(true)">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="UNREAD">未读</el-radio-button>
        <el-radio-button value="READ">已读</el-radio-button>
      </el-radio-group>
    </header>
    <ul class="list" v-loading="loading">
      <li v-for="item in list" :key="item.message_id" :class="{ unread: item.read_status === 'UNREAD', [`lv-${item.level}`]: true }">
        <span class="title">{{ item.title }}</span>
        <span class="meta">{{ item.type_code }} · {{ fmtTime(item.created_at) }}</span>
        <FcButton v-if="item.read_status === 'UNREAD'" variant="text" size="sm" @click="markRead(item)">标为已读</FcButton>
      </li>
      <!-- F-2：会话失效时显示重连提示，不呈现误导性「暂无消息」空态 -->
      <li v-if="!list.length && !loading && authError" class="empty auth-error">会话已失效，正在重新连接…</li>
      <li v-else-if="!list.length && !loading" class="empty">暂无消息</li>
    </ul>
    <footer v-if="hasMore">
      <FcButton variant="secondary" size="sm" :loading="loading" @click="load()">加载更多</FcButton>
    </footer>
  </section>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.list { list-style: none; margin: 0; padding: 0; }
.list li { display: flex; gap: 12px; align-items: center; padding: 10px 8px; border-bottom: 1px solid var(--el-border-color-lighter); }
.list li .title { flex: 1; }
.list li.unread .title { font-weight: 600; }
.list li.lv-URGENT .title { color: var(--el-color-danger); }
.list li.lv-IMPORTANT .title { color: var(--el-color-warning); }
.meta { color: var(--el-text-color-secondary); font-size: 12px; }
.empty { justify-content: center; color: var(--el-text-color-secondary); }
.empty.auth-error { color: var(--el-color-warning); }
footer { text-align: center; padding: 12px; }
</style>
