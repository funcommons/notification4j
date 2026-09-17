<template>
  <div class="app-page asset-postings-page">
    <FcSectionHeader :title="t('benefit.postings-title')" :subtitle="t('benefit.postings-subtitle')" />

    <FcFilterBar>
      <el-input
        v-model="accountRef"
        :placeholder="t('benefit.postings-account-ph')"
        style="width: 220px"
        clearable
      />
      <el-input
        v-model="assetCode"
        :placeholder="t('benefit.postings-asset-ph')"
        style="width: 140px"
        clearable
      />
      <template #actions>
        <FcButton size="small" type="primary" @click="onSearch">
          <i class="ri-search-line" /> {{ t('benefit.postings-search') }}
        </FcButton>
        <FcButton size="small" @click="onReset">
          <i class="ri-refresh-line" /> {{ t('benefit.postings-reset') }}
        </FcButton>
      </template>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table class="fc-table postings-table" :data="rows" v-loading="loading" row-key="id" stripe :max-height="560">
          <el-table-column label="TX ID" width="180">
            <template #default="{ row }">
              <code class="cell-id">{{ shortId(row.tx_id) }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-type')" width="110" align="center">
            <template #default="{ row }">
              <FcTag :type="txTagType(row.tx_type)">{{ row.tx_type }}</FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-order')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <code class="cell-id">{{ row.ext_order_id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-leg')" width="70" align="center">
            <template #default="{ row }">#{{ row.leg_seq }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-src-dst')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <code class="cell-id">{{ shortId(row.src_account_id) }}</code>
              <span class="arrow">→</span>
              <code class="cell-id">{{ shortId(row.dst_account_id) }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-amount')" width="130" align="right">
            <template #default="{ row }">
              <span class="amount">{{ row.amount }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-after')" width="130" align="right">
            <template #default="{ row }">
              <span class="after">{{ row.balance_after ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.postings-time')" width="180">
            <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
          </el-table-column>
        </el-table>
      </el-scrollbar>
      <FcEmpty v-if="!loading && rows.length === 0" :description="t('benefit.postings-empty')" />
      <FcPagination
        v-if="total > pageSize"
        v-model:current-page="page"
        :total="total"
        :page-size="pageSize"
        @current-change="loadPostings"
      />
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  FcSection, FcSectionHeader, FcFilterBar, FcButton, FcTag, FcEmpty, FcPagination, toast,
} from '@/components/sdk'
import { getAssetPostings } from '@/api/benefitAssets'
import type { AssetPosting } from '@/api/benefitAssets'

const { t } = useI18n()

const loading = ref(false)
const rows = ref<AssetPosting[]>([])
const accountRef = ref('')
const assetCode = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)

const shortId = (id?: string) => (id ? id.slice(-8) : '')
const formatTime = (v?: string) => (v ? v.replace('T', ' ').slice(0, 19) : '')
const txTagType = (type?: string) => {
  switch (type) {
    case 'ISSUE': case 'REPAY': return 'success'
    case 'CONSUME': return 'warning'
    case 'REFUND': case 'UNFREEZE': return 'info'
    case 'FREEZE': return 'danger'
    default: return 'info'
  }
}

const loadPostings = async () => {
  if (!accountRef.value || !assetCode.value) {
    toast.warning(t('benefit.postings-params-required'))
    return
  }
  loading.value = true
  try {
    const resp = await getAssetPostings({
      account_ref: accountRef.value.trim(),
      asset_code: assetCode.value.trim(),
      page: page.value,
      size: pageSize,
    })
    rows.value = (resp.data as any) || []
    // 服务端未回总数,用当前页满页估算翻页可用性
    total.value = rows.value.length === pageSize ? page.value * pageSize + 1 : page.value * pageSize
  } catch (e: any) {
    toast.error(e?.message || t('benefit.postings-load-failed'))
  } finally {
    loading.value = false
  }
}

const onSearch = () => {
  page.value = 1
  loadPostings()
}

const onReset = () => {
  accountRef.value = ''
  assetCode.value = ''
  rows.value = []
  total.value = 0
}

onMounted(() => {
  // 不预加载,等运营输入账户引用后查询(避免全表)
})
</script>

<style scoped>
.postings-table .arrow {
  margin: 0 6px;
  color: var(--el-text-color-secondary);
}
.amount {
  font-weight: 600;
}
.after {
  color: var(--el-text-color-secondary);
}
</style>
