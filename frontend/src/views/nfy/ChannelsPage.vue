<script setup lang="ts">
// 渠道页（API-CHN-001~005；PRD：卡片式=图标+状态+启停+验证+删除；注册对话框）
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useNfy, isTokenExpiredError } from '@/api/nfy/nfyContext'
import { NfyApiError } from '@/api/nfy/client'
import { FcButton, FcSelect } from '@/components/sdk/form'
import { FcTag } from '@/components/sdk/display'
import FcDialog from '@/components/sdk/overlay/FcDialog.vue'
import FcSectionCard from '@/components/sdk/display/FcSectionCard.vue'

const channelTypeOptions = [
  { label: '钉钉', value: 'DINGTALK' },
  { label: '企业微信', value: 'WECOM' },
  { label: '飞书', value: 'FEISHU' },
  { label: '邮箱', value: 'EMAIL' },
]

const { apis } = useNfy()
const list = ref<import('@/api/nfy').ChannelItem[]>([])
const loading = ref(false)
const dialog = ref(false)
const form = ref({ channel_type: 'DINGTALK', name: '', target: '', secret: '', keyword: '' })

async function load() {
  loading.value = true
  try {
    list.value = (await apis.channels.list()).list
  } finally {
    loading.value = false
  }
}

async function register() {
  try {
    const res = await apis.channels.register(form.value)
    ElMessage.success(res.verify_tip)
    dialog.value = false
    await load()
  } catch (e) {
    // 10100/10609 等参数与 SSRF 校验失败直接提示
    ElMessage.error(e instanceof NfyApiError ? e.message : '注册失败')
    if (isTokenExpiredError(e)) throw e
  }
}

async function verify(id: string) {
  try {
    await apis.channels.verify(id)
    ElMessage.success('验证成功')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof NfyApiError ? e.message : '验证失败')
  }
}

async function toggle(item: import('@/api/nfy').ChannelItem) {
  const next = item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    await apis.channels.patch(item.channel_id, { status: next })
    await load()
  } catch (e) {
    // 10610 未验证/熔断渠道启用被拒
    ElMessage.error(e instanceof NfyApiError ? e.message : '操作失败')
  }
}

async function remove(item: import('@/api/nfy').ChannelItem) {
  await apis.channels.remove(item.channel_id)
  await load()
}

onMounted(() => void load())
</script>

<template>
  <section>
    <header class="bar">
      <h2>我的渠道</h2>
      <FcButton variant="primary" size="sm" @click="dialog = true">注册渠道</FcButton>
    </header>

    <div v-loading="loading" class="cards">
      <FcSectionCard v-for="c in list" :key="c.channel_id">
        <div class="card">
          <div>
            <b>{{ c.name }}</b>
            <FcTag class="tag">{{ c.channel_type }}</FcTag>
            <FcTag :color="c.status === 'ENABLED' ? 'success' : c.status === 'PENDING' ? 'primary' : 'danger'">
              {{ c.status }}
            </FcTag>
          </div>
          <div class="target">{{ c.target }}</div>
          <div class="ops">
            <FcButton v-if="c.status !== 'ENABLED'" variant="text" size="sm" @click="verify(c.channel_id)">验证</FcButton>
            <FcButton v-if="c.status !== 'PENDING'" variant="text" size="sm" @click="toggle(c)">
              {{ c.status === 'ENABLED' ? '停用' : '启用' }}
            </FcButton>
            <FcButton v-if="c.status !== 'PENDING'" variant="text" size="sm" @click="remove(c)">删除</FcButton>
          </div>
        </div>
      </FcSectionCard>
      <p v-if="!list.length && !loading" class="empty">还没有渠道，点右上角注册</p>
    </div>

    <FcDialog v-model:open="dialog" title="注册渠道" width="480px">
      <el-form label-width="90px">
        <el-form-item label="类型" class="fc-form-item">
          <FcSelect v-model="form.channel_type" :options="channelTypeOptions" />
        </el-form-item>
        <el-form-item label="名称" class="fc-form-item"><el-input v-model="form.name" maxlength="30" class="fc-input" /></el-form-item>
        <el-form-item label="目标" class="fc-form-item">
          <el-input v-model="form.target" class="fc-input" :placeholder="form.channel_type === 'EMAIL' ? '邮箱地址' : 'https:// webhook 地址'" />
        </el-form-item>
        <el-form-item label="加签密钥" class="fc-form-item"><el-input v-model="form.secret" maxlength="128" class="fc-input" /></el-form-item>
        <el-form-item label="关键词" class="fc-form-item"><el-input v-model="form.keyword" maxlength="20" class="fc-input" /></el-form-item>
      </el-form>
      <template #footer>
        <FcButton variant="secondary" @click="dialog = false">取消</FcButton>
        <FcButton variant="primary" @click="register">注册（仅落库，验证后生效）</FcButton>
      </template>
    </FcDialog>
  </section>
</template>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
h2 { margin: 0; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.card { display: flex; flex-direction: column; gap: 8px; }
.tag { margin: 0 4px; }
.target { color: var(--el-text-color-secondary); font-size: 12px; word-break: break-all; }
.ops { display: flex; gap: 4px; }
.empty { text-align: center; color: var(--el-text-color-secondary); grid-column: 1/-1; }
</style>
