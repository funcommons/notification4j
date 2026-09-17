<script setup lang="ts">
// 投递记录页（API-DLV-001/002，§5.9.2 管理面；V1.2 人工重投）：
// 筛选（userid/biz_no/channel_type/status）+ Offset 分页表格；仅 DEAD 行可重投。
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useNfy, isTokenExpiredError } from '@/api/nfy/nfyContext'
import { NfyApiError } from '@/api/nfy/client'
import { FcButton, FcSelect } from '@/components/sdk/form'
import { FcTag } from '@/components/sdk/display'
import { FcPagination } from '@/components/sdk/navigation'
import { parseFwkTime } from '@/utils/fwkTime'
import type { DeliveryItem } from '@/api/nfy'

const channelTypeOptions = [
  { label: '站内信', value: 'INAPP' },
  { label: '钉钉', value: 'DINGTALK' },
  { label: '企业微信', value: 'WECOM' },
  { label: '飞书', value: 'FEISHU' },
  { label: '邮箱', value: 'EMAIL' },
]

const statusOptions = ['PENDING', 'SENDING', 'SUCCESS', 'FAILED', 'DEAD', 'CANCELLED']
  .map((s) => ({ label: s, value: s }))

/** 状态 → FcTag 色：SUCCESS 绿 / FAILED 橙 / DEAD 红 / PENDING·SENDING 蓝 / CANCELLED 灰 */
const statusColor: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'gray'> = {
  SUCCESS: 'success',
  FAILED: 'warning',
  DEAD: 'danger',
  PENDING: 'primary',
  SENDING: 'primary',
  CANCELLED: 'gray',
}

/** 10402 等错误的兜底文案（后端 message 缺失/非信封错误时） */
const RETRY_FALLBACK_TIP = '仅 DEAD 状态可重投'

const { apis } = useNfy()
const list = ref<DeliveryItem[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filters = ref({ userid: '', biz_no: '', channel_type: '', status: '' })

async function load() {
  loading.value = true
  try {
    const res = await apis.deliveries.list({
      userid: filters.value.userid || undefined,
      biz_no: filters.value.biz_no || undefined,
      channel_type: filters.value.channel_type || undefined,
      status: filters.value.status || undefined,
      offset: (page.value - 1) * size.value,
      limit: size.value,
    })
    list.value = res.list
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof NfyApiError ? e.message : '投递记录加载失败')
    if (isTokenExpiredError(e)) throw e
  } finally {
    loading.value = false
  }
}

/** 查询：筛选条件变化回到第 1 页（Offset 语义） */
function search() {
  page.value = 1
  void load()
}

function resetFilters() {
  filters.value = { userid: '', biz_no: '', channel_type: '', status: '' }
  page.value = 1
  void load()
}

function onPageChange(p: number, s: number) {
  page.value = p
  size.value = s
  void load()
}

/** 后端时间为 Long→String 数字字符串（fwk 精度保护），必须经 parseFwkTime 归一 */
const fmtTime = (v: DeliveryItem['created_at']) => parseFwkTime(v)?.toLocaleString() ?? ''

/** DLV-002 人工重投：成功提示 + 刷新当前页；非 DEAD 10402 → 错误文案 */
async function retry(item: DeliveryItem) {
  try {
    await apis.deliveries.retry(item.delivery_id)
    ElMessage.success(`投递 ${item.delivery_id} 已重新入队`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof NfyApiError ? e.message || RETRY_FALLBACK_TIP : RETRY_FALLBACK_TIP)
    if (isTokenExpiredError(e)) throw e
  }
}

onMounted(() => void load())
</script>

<template>
  <section>
    <header class="bar">
      <h2>投递记录</h2>
    </header>

    <div class="filters">
      <el-input v-model="filters.userid" class="fc-input filter-item" placeholder="userid" clearable @keyup.enter="search" />
      <el-input v-model="filters.biz_no" class="fc-input filter-item" placeholder="biz_no" clearable @keyup.enter="search" />
      <FcSelect v-model="filters.channel_type" class="filter-item" :options="channelTypeOptions" placeholder="渠道类型" clearable />
      <FcSelect v-model="filters.status" class="filter-item" :options="statusOptions" placeholder="状态" clearable />
      <FcButton variant="primary" size="sm" @click="search">查询</FcButton>
      <FcButton variant="secondary" size="sm" @click="resetFilters">重置</FcButton>
    </div>

    <el-table v-loading="loading" :data="list" size="small" class="fc-table">
      <el-table-column prop="delivery_id" label="投递ID" width="150" />
      <el-table-column prop="userid" label="用户" min-width="110" show-overflow-tooltip />
      <el-table-column prop="channel_type" label="渠道" width="100" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <FcTag :color="statusColor[row.status] ?? 'gray'">{{ row.status }}</FcTag>
        </template>
      </el-table-column>
      <el-table-column prop="retry_count" label="重试次数" width="90" align="center" />
      <el-table-column prop="error_message" label="错误信息" min-width="180" show-overflow-tooltip />
      <el-table-column label="发送时间" width="165">
        <template #default="{ row }">{{ fmtTime(row.sent_at) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="165">
        <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <FcButton v-if="row.status === 'DEAD'" variant="primary" size="sm" @click="retry(row)">重投</FcButton>
        </template>
      </el-table-column>
      <template #empty>
        <span class="empty">暂无投递记录</span>
      </template>
    </el-table>

    <footer class="pager">
      <FcPagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        @change="onPageChange"
      />
    </footer>
  </section>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
h2 { margin: 0; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.filter-item { width: 160px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.empty { color: var(--el-text-color-secondary); }
</style>
