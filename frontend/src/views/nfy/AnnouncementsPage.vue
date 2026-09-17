<script setup lang="ts">
import { FcButton } from '@/components/sdk/form'
import { FcTag } from '@/components/sdk/display'
// 公告页（API-ANN-001/002/003）：平台+本租户合并列表，need_confirm 显示「我知道了」
import { ref, onMounted } from 'vue'
import { useNfy } from '@/api/nfy/nfyContext'

const { apis } = useNfy()
const list = ref<import('@/api/nfy').AnnouncementItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)

async function load(reset = false) {
  loading.value = true
  try {
    const res = await apis.announcements.list({ cursor: reset ? undefined : cursor.value ?? undefined, limit: 20 })
    list.value = reset ? res.list : [...list.value, ...res.list]
    cursor.value = res.next_cursor
    hasMore.value = res.has_more
  } finally {
    loading.value = false
  }
}

async function markRead(a: import('@/api/nfy').AnnouncementItem) {
  await apis.announcements.markRead(a.announcement_id)
  if (a.my_status === 'NONE') a.my_status = 'READ'
}

async function confirm(a: import('@/api/nfy').AnnouncementItem) {
  await apis.announcements.confirm(a.announcement_id)
  a.my_status = 'CONFIRMED'
}

onMounted(() => void load(true))
</script>

<template>
  <section>
    <h2>公告</h2>
    <ul class="list" v-loading="loading">
      <li v-for="a in list" :key="a.announcement_id">
        <header>
          <b>{{ a.title }}</b>
          <FcTag v-if="a.scope === 'PLATFORM'">平台</FcTag>
          <FcTag v-if="a.need_confirm === 1" color="warning">需确认</FcTag>
        </header>
        <p>{{ a.content }}</p>
        <footer>
          <span class="time">{{ a.published_at ? new Date(a.published_at).toLocaleString() : '' }}</span>
          <FcButton v-if="a.my_status === 'NONE'" variant="text" size="sm" @click="markRead(a)">标为已读</FcButton>
          <FcButton v-if="a.need_confirm === 1 && a.my_status !== 'CONFIRMED'" variant="primary" size="sm" @click="confirm(a)">
            我知道了
          </FcButton>
          <FcTag v-else-if="a.my_status === 'CONFIRMED'" color="success">已确认</FcTag>
        </footer>
      </li>
      <li v-if="!list.length && !loading" class="empty">暂无公告</li>
    </ul>
    <FcButton v-if="hasMore" variant="secondary" size="sm" :loading="loading" @click="load()">加载更多</FcButton>
  </section>
</template>

<style scoped>
h2 { margin: 0 0 12px; }
.list { list-style: none; margin: 0; padding: 0; }
.list li { padding: 12px 8px; border-bottom: 1px solid var(--el-border-color-lighter); }
.list li header { display: flex; gap: 8px; align-items: center; }
.list li p { margin: 8px 0; color: var(--el-text-color-regular); white-space: pre-wrap; }
.list li footer { display: flex; gap: 8px; align-items: center; }
.time { color: var(--el-text-color-secondary); font-size: 12px; margin-right: auto; }
.empty { text-align: center; color: var(--el-text-color-secondary); }
</style>
