<script setup lang="ts">
import { FcButton } from '@/components/sdk/form'
// 消息页（API-MSG-004/006/007；PRD：未读加粗+等级色标+已读操作，Cursor 加载更多）
import { ref, onMounted } from 'vue'
import { useNfy } from '@/api/nfy/nfyContext'

const { apis } = useNfy()
const list = ref<import('@/api/nfy').MessageItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const readFilter = ref<'' | 'UNREAD' | 'READ'>('')
const unread = ref(0)

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

async function markRead(item: import('@/api/nfy').MessageItem) {
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
        <span class="meta">{{ item.type_code }} · {{ new Date(item.created_at ?? 0).toLocaleString() }}</span>
        <FcButton v-if="item.read_status === 'UNREAD'" variant="text" size="sm" @click="markRead(item)">标为已读</FcButton>
      </li>
      <li v-if="!list.length && !loading" class="empty">暂无消息</li>
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
footer { text-align: center; padding: 12px; }
</style>
