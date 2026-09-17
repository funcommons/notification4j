<script setup lang="ts">
import { FcButton } from '@/components/sdk/form'
import { FcTag } from '@/components/sdk/display'
// 公告页（API-ANN-001/002/003）：平台+本租户合并列表，need_confirm 显示「我知道了」
import { ref, onMounted } from 'vue'
import { useNfy } from '@/api/nfy/nfyContext'
import { parseFwkTime } from '@/utils/fwkTime'
import type { AnnouncementItem } from '@/api/nfy'

const { apis, authError } = useNfy()
const list = ref<AnnouncementItem[]>([])
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

/** 后端时间为 Long→String 数字字符串（fwk 精度保护），必须经 parseFwkTime 归一 */
const fmtTime = (v: AnnouncementItem['published_at']) => parseFwkTime(v)?.toLocaleString() ?? ''

async function markRead(a: AnnouncementItem) {
  await apis.announcements.markRead(a.announcement_id)
  if (a.my_status === 'NONE') a.my_status = 'READ'
}

async function confirm(a: AnnouncementItem) {
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
          <span class="time">{{ fmtTime(a.published_at) }}</span>
          <FcButton v-if="a.my_status === 'NONE'" variant="text" size="sm" @click="markRead(a)">标为已读</FcButton>
          <FcButton v-if="a.need_confirm === 1 && a.my_status !== 'CONFIRMED'" variant="primary" size="sm" @click="confirm(a)">
            我知道了
          </FcButton>
          <FcTag v-else-if="a.my_status === 'CONFIRMED'" color="success">已确认</FcTag>
        </footer>
      </li>
      <!-- F-2：会话失效时显示重连提示，不呈现误导性「暂无公告」空态 -->
      <li v-if="!list.length && !loading && authError" class="empty auth-error">会话已失效，正在重新连接…</li>
      <li v-else-if="!list.length && !loading" class="empty">暂无公告</li>
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
.empty.auth-error { color: var(--el-color-warning); }
</style>
