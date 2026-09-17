<template>
  <div class="app-page assets-page">
    <FcSectionHeader :title="t('benefit.assets-title')" :subtitle="t('benefit.assets-subtitle')" />

    <FcFilterBar>
      <FcSelect
        v-model="filterType"
        :placeholder="t('benefit.assets-filter-type')"
        :options="typeOptions"
        style="width: 160px"
        clearable
      />
      <FcSelect
        v-model="filterStatus"
        :placeholder="t('benefit.assets-filter-status')"
        :options="statusOptions"
        style="width: 140px"
        clearable
      />
      <div class="search-wrapper">
        <i class="ri-search-line search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('benefit.search-placeholder')"
          class="search-input"
        />
      </div>
      <template #actions>
        <FcButton size="sm" @click="onRefresh">
          <i class="ri-refresh-line" /> {{ t('benefit.action-refresh') }}
        </FcButton>
        <FcButton size="sm" type="primary" @click="openCreate">
          <i class="ri-add-line" /> {{ t('benefit.assets-create') }}
        </FcButton>
      </template>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table class="fc-table assets-table" :data="rows" v-loading="loading" row-key="code" stripe :max-height="560">
          <el-table-column :label="t('benefit.assets-code')" width="140">
            <template #default="{ row }">
              <code class="cell-id">{{ row.code }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.item-name')" min-width="140">
            <template #default="{ row }">{{ row.name }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.assets-type')" width="110" align="center">
            <template #default="{ row }">
              <FcTag :type="row.asset_type === 'FIAT' ? 'danger' : 'info'">{{ row.asset_type }}</FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.assets-precision')" width="90" align="center">
            <template #default="{ row }">{{ row.precision }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.assets-caps')" min-width="220">
            <template #default="{ row }">
              <span class="caps">
                <FcTag v-if="row.can_pay" type="success" size="sm">PAY</FcTag>
                <FcTag v-if="row.can_transfer" type="warning" size="sm">TRANSFER</FcTag>
                <FcTag v-if="row.can_exchange" size="sm">EXCHANGE</FcTag>
                <FcTag v-if="row.can_credit" type="danger" size="sm">CREDIT</FcTag>
                <FcTag v-if="row.can_withdraw" type="danger" size="sm">WITHDRAW</FcTag>
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.assets-limit')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="limit-summary">{{ limitSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.status')" width="100" align="center">
            <template #default="{ row }">
              <FcTag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.actions')" width="140" fixed="right">
            <template #default="{ row }">
              <FcButton v-if="row.status === 'ACTIVE'" size="sm" type="danger" plain @click="onToggle(row, 'suspend')">
                {{ t('benefit.assets-suspend') }}
              </FcButton>
              <FcButton v-else size="sm" type="success" plain @click="onToggle(row, 'resume')">
                {{ t('benefit.assets-resume') }}
              </FcButton>
            </template>
          </el-table-column>
        </el-table>
      </el-scrollbar>
      <FcEmpty v-if="!loading && rows.length === 0" :description="t('benefit.assets-empty')" />
    </FcSection>

    <el-dialog v-model="createVisible" :title="t('benefit.assets-create')" width="560px">
      <el-form :model="form" label-width="130px">
        <el-form-item :label="t('benefit.assets-code')" required>
          <el-input v-model="form.code" :placeholder="t('benefit.assets-code-ph')" maxlength="32" />
        </el-form-item>
        <el-form-item :label="t('benefit.item-name')" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('benefit.assets-type')">
          <el-select v-model="form.asset_type" style="width: 100%">
            <el-option label="VIRTUAL" value="VIRTUAL" />
            <el-option label="FIAT" value="FIAT" disabled />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('benefit.assets-precision')">
          <el-input-number v-model="form.precision" :min="0" :max="6" />
        </el-form-item>
        <el-form-item :label="t('benefit.assets-caps')">
          <span class="caps-form">
            <el-checkbox v-model="form.can_pay">PAY</el-checkbox>
            <el-checkbox v-model="form.can_exchange">EXCHANGE</el-checkbox>
            <el-checkbox v-model="form.can_transfer">TRANSFER</el-checkbox>
            <el-checkbox v-model="form.can_credit">CREDIT</el-checkbox>
          </span>
        </el-form-item>
        <el-form-item :label="t('benefit.description')">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item :label="t('benefit.assets-limit')">
          <el-input
            v-model="limitPolicyText"
            type="textarea"
            :rows="2"
            :placeholder='`{"singleMax":"10000.00","dailyMax":"50000.00"}`'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <FcButton @click="createVisible = false">{{ t('benefit.action-cancel') }}</FcButton>
        <FcButton type="primary" :loading="submitting" @click="onSubmit">{{ t('benefit.action-confirm') }}</FcButton>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  FcSection, FcSectionHeader, FcFilterBar, FcButton, FcSelect, FcTag, FcEmpty, toast,
} from '@/components/sdk'
import { getAssets, createAsset, suspendAsset, resumeAsset } from '@/api/benefitAssets'
import type { AssetDefinition } from '@/api/benefitAssets'

const { t } = useI18n()

const loading = ref(false)
const submitting = ref(false)
const assets = ref<AssetDefinition[]>([])
const filterType = ref('')
const filterStatus = ref('')
const searchQuery = ref('')
const createVisible = ref(false)
const limitPolicyText = ref('')

const form = ref<Partial<AssetDefinition>>({
  code: '',
  name: '',
  asset_type: 'VIRTUAL',
  precision: 0,
  can_pay: true,
  can_exchange: true,
  can_transfer: false,
  can_credit: false,
  description: '',
})

const typeOptions = [
  { label: 'VIRTUAL', value: 'VIRTUAL' },
  { label: 'FIAT', value: 'FIAT' },
]
const statusOptions = [
  { label: 'ACTIVE', value: 'ACTIVE' },
  { label: 'SUSPEND', value: 'SUSPEND' },
]

const rows = computed(() => {
  let list = assets.value
  if (filterType.value) list = list.filter(a => a.asset_type === filterType.value)
  if (filterStatus.value) list = list.filter(a => a.status === filterStatus.value)
  const q = searchQuery.value.trim().toUpperCase()
  if (q) list = list.filter(a => (a.code || '').includes(q) || (a.name || '').toUpperCase().includes(q))
  return list
})

const limitSummary = (row: AssetDefinition) => {
  const lp = row.limit_policy as Record<string, any> | undefined
  if (!lp || Object.keys(lp).length === 0) return '—'
  return Object.entries(lp)
    .filter(([, v]) => typeof v !== 'object')
    .map(([k, v]) => `${k}=${v}`)
    .join(' ') || 'directional'
}

const loadAssets = async () => {
  loading.value = true
  try {
    const resp = await getAssets()
    assets.value = (resp.data as any) || []
  } catch (e: any) {
    toast.error(e?.message || t('benefit.assets-load-failed'))
  } finally {
    loading.value = false
  }
}

const onRefresh = () => loadAssets()

const openCreate = () => {
  form.value = {
    code: '',
    name: '',
    asset_type: 'VIRTUAL',
    precision: 0,
    can_pay: true,
    can_exchange: true,
    can_transfer: false,
    can_credit: false,
    description: '',
  }
  limitPolicyText.value = ''
  createVisible.value = true
}

const onSubmit = async () => {
  const f = form.value
  if (!f.code || !f.name) {
    toast.warning(t('benefit.assets-form-required'))
    return
  }
  submitting.value = true
  try {
    const payload: Partial<AssetDefinition> = { ...f }
    if (limitPolicyText.value.trim()) {
      try {
        payload.limit_policy = JSON.parse(limitPolicyText.value)
      } catch {
        toast.warning(t('benefit.assets-limit-invalid'))
        return
      }
    }
    await createAsset(payload)
    toast.success(t('benefit.assets-created'))
    createVisible.value = false
    await loadAssets()
  } catch (e: any) {
    toast.error(e?.message || t('benefit.assets-create-failed'))
  } finally {
    submitting.value = false
  }
}

const onToggle = async (row: AssetDefinition, action: 'suspend' | 'resume') => {
  try {
    if (action === 'suspend') await suspendAsset(row.code!)
    else await resumeAsset(row.code!)
    toast.success(t('benefit.assets-toggled'))
    await loadAssets()
  } catch (e: any) {
    toast.error(e?.message || t('benefit.assets-toggle-failed'))
  }
}

onMounted(loadAssets)
</script>

<style scoped>
.assets-table :deep(.caps) {
  display: inline-flex;
  gap: 4px;
  flex-wrap: wrap;
}
.caps-form {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.limit-summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
