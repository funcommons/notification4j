<script setup lang="ts">
import { FcButton } from '@/components/sdk/form'
import { FcTag } from '@/components/sdk/display'
// 订阅页（API-SUB-001/002；PRD：行=类型，列=渠道，格=开关；强制类型锁提示；INAPP 恒选）
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useNfy } from '@/api/nfy/nfyContext'
import type { SubscriptionMatrix } from '@/api/nfy'

const { apis } = useNfy()
const matrix = ref<SubscriptionMatrix | null>(null)
const loading = ref(false)
const saving = ref(false)
/** type_code → 选中的 channel_id 集合（含 INAPP 哨兵） */
const picks = ref<Record<string, Set<string>>>({})

const channelColumns = computed(() => {
  if (!matrix.value) return []
  return matrix.value.available_channels.filter((c) => c.channel_id !== 'INAPP')
})

/** 强制类型：default_channels 里的渠道类型语义，用户有该类型实例则至少保留一个 */
const mandatoryHint = (typeCode: string) => {
  const t = matrix.value?.types.find((x) => x.type_code === typeCode)
  return t?.mandatory === 1 ? '强制类型：不能关闭全部站外渠道' : ''
}

function cellDisabled(typeCode: string, channelId: string): boolean {
  if (channelId === 'INAPP') return true // INAPP 锁定恒选
  const t = matrix.value?.types.find((x) => x.type_code === typeCode)
  if (t?.mandatory !== 1) return false
  const mandatoryTypes = new Set((t.default_channels ?? []).filter((c) => c !== 'INAPP'))
  const chosen = picks.value[typeCode]
  // 若该渠道是此类型在选集中唯一的强制类型实例 → 禁止关闭
  for (const col of channelColumns.value) {
    if (col.channel_id === channelId) continue
    if (mandatoryTypes.has(col.channel_type ?? '') && chosen?.has(col.channel_id)) return false
  }
  return mandatoryTypes.size > 0 && chosen?.has(channelId) === true && belongsToMandatory(channelId, mandatoryTypes)
}

function belongsToMandatory(channelId: string, mandatoryTypes: Set<string>): boolean {
  const col = channelColumns.value.find((c) => c.channel_id === channelId)
  return col ? mandatoryTypes.has(col.channel_type ?? '') : false
}

async function load() {
  loading.value = true
  try {
    matrix.value = await apis.subscriptions.get()
    const next: Record<string, Set<string>> = {}
    for (const t of matrix.value.types) next[t.type_code] = new Set(['INAPP'])
    for (const item of matrix.value.items) next[item.type_code] = new Set(item.channel_ids)
    picks.value = next
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const items = Object.entries(picks.value).map(([type_code, ids]) => ({
      type_code,
      channel_ids: [...ids],
    }))
    const res = await apis.subscriptions.save(items)
    ElMessage.success(`已保存 ${res.saved_count} 项`)
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section>
    <header class="bar">
      <h2>订阅偏好</h2>
      <FcButton variant="primary" size="sm" :loading="saving" @click="save">保存</FcButton>
    </header>
    <el-table v-if="matrix" :data="matrix.types" v-loading="loading" size="small" class="fc-table">
      <el-table-column label="消息类型" min-width="180">
        <template #default="{ row }">
          {{ row.name }}
          <FcTag v-if="row.mandatory === 1" color="warning">强制</FcTag>
        </template>
      </el-table-column>
      <el-table-column label="站内信" width="80" align="center">
        <template #default>
          <el-checkbox model-value disabled title="站内信恒开" />
        </template>
      </el-table-column>
      <el-table-column v-for="col in channelColumns" :key="col.channel_id" :label="col.name" min-width="120" align="center">
        <template #default="{ row }">
          <el-checkbox
            :model-value="picks[row.type_code]?.has(col.channel_id) ?? false"
            :disabled="cellDisabled(row.type_code, col.channel_id)"
            :title="mandatoryHint(row.type_code)"
            @update:model-value="(v: boolean) => { const set = picks[row.type_code]; if (!set) return; v ? set.add(col.channel_id) : set.delete(col.channel_id) }"
          />
        </template>
      </el-table-column>
      <el-table-column label="未配置时" width="90" align="center">
        <template #default="{ row }: { row: { default_channels?: string[] } }">
          <span class="default-channels">{{ (row.default_channels ?? []).join('/') }}</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
h2 { margin: 0; }
.default-channels { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
