<template>
  <div class="app-page embed-test-page">
    <FcSectionHeader title="嵌入集成测试" subtitle="iframe 嵌入方案调试工具 — 模拟三方前端嵌入 benefit4j 页面" />

    <!-- 配置面板 -->
    <FcSection>
      <el-form :inline="false" label-width="120px" class="config-form" @submit.prevent>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="端">
              <FcSelect v-model="cfg.side" :options="sideOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="入口模式">
              <FcSelect v-model="cfg.entry" :options="entryOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="目标页面">
              <FcSelect v-model="cfg.page" :options="pageOptions" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="品牌">
              <FcSelect v-model="cfg.brand" :options="brandOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="主题">
              <FcSelect v-model="cfg.mode" :options="modeOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="语言">
              <FcSelect v-model="cfg.language" :options="languageOptions" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item class="fc-form-item" label="认证方式">
              <FcSelect v-model="cfg.auth" :options="authOptions" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item class="fc-form-item" label="Access Token">
              <el-input
                v-model="cfg.token"
                class="fc-input"
                placeholder="留空则使用当前登录 token"
                clearable
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </FcSection>

    <!-- 生成结果 -->
    <FcSection>
      <div class="result-bar">
        <div class="result-url">
          <code>{{ iframeSrc || '—' }}</code>
        </div>
        <div class="result-actions">
          <FcButton size="small" @click="onCopyUrl">
            <i class="ri-link" /> 复制 URL
          </FcButton>
          <FcButton size="small" @click="onCopyHtml">
            <i class="ri-code-s-slash-line" /> 复制 &lt;iframe&gt;
          </FcButton>
          <FcButton size="small" type="primary" @click="onReload">
            <i class="ri-refresh-line" /> 重载
          </FcButton>
        </div>
      </div>
    </FcSection>

    <!-- 预览 + 日志 -->
    <div class="preview-row">
      <FcSection class="preview-section">
        <template #header>
          <div class="preview-header">
            <span>预览</span>
            <FcTag v-if="cfg.auth === 'postmessage'" :color="pmColor" size="sm">{{ pmStatus }}</FcTag>
          </div>
        </template>
        <div class="iframe-wrap">
          <iframe
            v-if="iframeSrc"
            ref="iframeRef"
            :src="iframeSrc"
            class="embed-iframe"
            @load="onIframeLoad"
          />
          <div v-else class="iframe-placeholder">配置参数后自动生成预览</div>
        </div>
      </FcSection>

      <FcSection class="log-section">
        <template #header>
          <div class="preview-header">
            <span>postMessage 日志</span>
            <FcButton size="small" text @click="logs = []">清空</FcButton>
          </div>
        </template>
        <div class="log-list">
          <div v-for="(log, i) in logs" :key="i" class="log-item" :class="log.dir">
            <span class="log-dir">{{ log.dir === 'in' ? '⬅' : '➡' }}</span>
            <span class="log-type">{{ log.type }}</span>
            <span class="log-detail">{{ log.detail }}</span>
            <span class="log-time">{{ log.time }}</span>
          </div>
          <div v-if="!logs.length" class="log-empty">暂无消息</div>
        </div>
      </FcSection>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import {
  FcSection, FcSectionHeader, FcButton, FcSelect, FcTag, toast,
} from '@/components/sdk'
import { copySilent } from '@/composables'
import { useBenefitAuthStore } from '@/store/benefitAuth'

defineOptions({ name: 'EmbedTestDemo' })

const benefitAuth = useBenefitAuthStore()

// —— 配置 ——
const cfg = reactive({
  side: 'platform' as 'platform' | 'tenant',
  entry: 'page' as 'app' | 'page',
  page: 'dashboard',
  brand: '',
  mode: '',
  language: '',
  auth: 'postmessage' as 'basic' | 'postmessage' | 'none',
  token: '',
})

const sideOptions = [
  { label: '平台端 (platform)', value: 'platform' },
  { label: '租户端 (tenant)', value: 'tenant' },
]
const entryOptions = [
  { label: 'page — 单页 (无侧栏)', value: 'page' },
  { label: 'app — 完整 (带侧栏)', value: 'app' },
]
const brandOptions = [
  { label: '(默认)', value: '' },
  { label: 'ldx2', value: 'ldx2' },
  { label: 'apple', value: 'apple' },
  { label: 'google', value: 'google' },
  { label: 'mchuan', value: 'mchuan' },
  { label: 'manyun', value: 'manyun' },
  { label: 'acme', value: 'acme' },
  { label: 'microsoft', value: 'microsoft' },
  { label: 'vonnex', value: 'vonnex' },
]
const modeOptions = [
  { label: '(默认)', value: '' },
  { label: 'light', value: 'light' },
  { label: 'dark', value: 'dark' },
]
const languageOptions = [
  { label: '(默认)', value: '' },
  { label: 'zh-CN', value: 'zh-CN' },
  { label: 'en-US', value: 'en-US' },
]
const authOptions = [
  { label: '推荐级 — postMessage 握手', value: 'postmessage' },
  { label: '基础级 — URL 带 token', value: 'basic' },
  { label: '无认证', value: 'none' },
]

const platformPages = [
  { label: 'dashboard', value: 'dashboard' },
  { label: 'apps', value: 'apps' },
  { label: 'items', value: 'items' },
  { label: 'templates/set', value: 'templates/set' },
  { label: 'templates/item', value: 'templates/item' },
  { label: 'sets', value: 'sets' },
  { label: 'subscriptions', value: 'subscriptions' },
  { label: 'consumptions', value: 'consumptions' },
]
const tenantPages = [
  { label: 'dashboard', value: 'dashboard' },
  { label: 'items', value: 'items' },
  { label: 'templates', value: 'templates' },
  { label: 'sets', value: 'sets' },
  { label: 'subscriptions', value: 'subscriptions' },
  { label: 'consumptions', value: 'consumptions' },
]

const pageOptions = computed(() =>
  cfg.side === 'platform' ? platformPages : tenantPages,
)

// 切端时重置页面 (避免选了 platform 独有页)
watch(() => cfg.side, () => { cfg.page = 'dashboard' })

// —— URL 生成 ——
const iframeSrc = computed(() => {
  const base = `/benefit/${cfg.side}/${cfg.entry}/${cfg.page}`
  const params = new URLSearchParams()
  if (cfg.brand) params.set('brand', cfg.brand)
  if (cfg.mode) params.set('mode', cfg.mode)
  if (cfg.language) params.set('language', cfg.language)
  if (cfg.auth === 'basic') {
    const token = cfg.token || benefitAuth.token || ''
    if (token) params.set('access_token', token)
  }
  const qs = params.toString()
  return qs ? `${base}?${qs}` : base
})

// —— iframe 引用 & 重载 ——
const iframeRef = ref<HTMLIFrameElement | null>(null)
const iframeKey = ref(0)

const onReload = () => {
  iframeKey.value++
  // 强制重建 iframe (v-if 闪断)
  const el = iframeRef.value
  if (el) {
    el.src = el.src // 触发 iframe 重新加载
  }
}

const onIframeLoad = () => {
  addLog('sys', 'iframe loaded', '')
}

// —— postMessage 父页协议 ——
const pmStatus = ref('等待 READY')
const pmColor = computed(() => {
  if (pmStatus.value === '已连接') return 'success'
  if (pmStatus.value === '等待 READY') return 'warning'
  return 'gray'
})

interface LogItem {
  dir: 'in' | 'out' | 'sys'
  type: string
  detail: string
  time: string
}
const logs = ref<LogItem[]>([])

const addLog = (dir: LogItem['dir'], type: string, detail: string) => {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const time = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  logs.value.unshift({ dir, type, detail, time })
  if (logs.value.length > 50) logs.value.pop()
}

const handleParentMessage = (e: MessageEvent) => {
  const data = e.data
  if (!data || typeof data !== 'object') return

  if (data.type === 'READY') {
    addLog('in', 'READY', `from ${e.origin}`)
    pmStatus.value = '收到 READY'

    if (cfg.auth === 'postmessage') {
      sendToken()
    }
  }

  if (data.type === 'RENEW') {
    addLog('in', 'RENEW', `from ${e.origin}`)
    pmStatus.value = '收到 RENEW'
    sendToken()
  }
}

const sendToken = () => {
  const iframe = iframeRef.value
  if (!iframe?.contentWindow) {
    addLog('sys', 'ERROR', 'iframe 不可用')
    return
  }
  const token = cfg.token || benefitAuth.token || ''
  if (!token) {
    addLog('sys', 'ERROR', '无可用 token — 请先登录或手动填入')
    return
  }
  const msg = { type: 'TOKEN', access_token: token, expires_in: 3600 }
  iframe.contentWindow.postMessage(msg, '*')
  addLog('out', 'TOKEN', `access_token=${token.slice(0, 12)}… expires_in=3600`)
  pmStatus.value = '已连接'
}

onMounted(() => {
  window.addEventListener('message', handleParentMessage)
})
onUnmounted(() => {
  window.removeEventListener('message', handleParentMessage)
})

// —— 复制 ——
const onCopyUrl = async () => {
  const ok = await copySilent(iframeSrc.value)
  if (ok) toast.success('URL 已复制')
}

const onCopyHtml = async () => {
  const origin = window.location.origin
  const html = `<iframe
  src="${origin}${iframeSrc.value}"
  style="width: 100%; height: 600px; border: 1px solid #e5e7eb; border-radius: 12px;"
  allow="clipboard-write"
></iframe>`
  const ok = await copySilent(html)
  if (ok) toast.success('<iframe> 代码已复制')
}
</script>

<style scoped lang="scss">
.embed-test-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

.config-form {
  :deep(.el-form-item) { margin-bottom: 12px; }
}

.result-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.result-url {
  flex: 1;
  min-width: 0;
  code {
    display: block;
    padding: 8px 12px;
    background: var(--app-bg-muted, #f5f5f7);
    border-radius: 6px;
    font-size: 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    word-break: break-all;
    color: var(--app-text);
  }
}
.result-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.preview-row {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
}
.preview-section {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  font-weight: 600;
}

.iframe-wrap {
  flex: 1;
  min-height: 520px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
  background: var(--app-bg-page, #f5f5f7);
}
.embed-iframe {
  width: 100%;
  height: 100%;
  min-height: 520px;
  border: none;
  display: block;
}
.iframe-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 520px;
  color: var(--app-text-tertiary);
  font-size: 14px;
}

.log-section {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.log-list {
  flex: 1;
  overflow-y: auto;
  max-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.log-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  &.in { background: color-mix(in srgb, var(--el-color-success) 8%, transparent); }
  &.out { background: color-mix(in srgb, var(--el-color-primary) 8%, transparent); }
  &.sys { background: var(--app-bg-muted, #f5f5f7); }
}
.log-dir { flex-shrink: 0; }
.log-type { font-weight: 600; flex-shrink: 0; }
.log-detail {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-text-secondary);
}
.log-time {
  flex-shrink: 0;
  color: var(--app-text-tertiary);
}
.log-empty {
  padding: 24px;
  text-align: center;
  color: var(--app-text-tertiary);
  font-size: 13px;
}

</style>
