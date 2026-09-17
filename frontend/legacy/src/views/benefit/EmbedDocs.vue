<template>
  <div class="embed-docs">
    <FcSectionHeader :title="t('embedDocs.title')" :subtitle="t('embedDocs.subtitle')" />

    <!-- 概述 -->
    <FcSection>
      <p class="doc-para">{{ t('embedDocs.overview') }}</p>
    </FcSection>

    <!-- 可用路由 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.routes-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.routes-intro') }}</p>
      <el-table class="fc-table" :data="routes" border stripe size="small">
        <el-table-column prop="mode" :label="t('embedDocs.th-mode')" min-width="150" />
        <el-table-column :label="t('embedDocs.th-prefix')" min-width="210">
          <template #default="{ row }"><code class="inline-code">{{ row.prefix }}</code></template>
        </el-table-column>
        <el-table-column prop="layout" :label="t('embedDocs.th-layout')" width="110" />
        <el-table-column prop="desc" :label="t('embedDocs.th-desc')" min-width="230" />
      </el-table>
      <h4 class="doc-sub">{{ t('embedDocs.subpages-title') }}</h4>
      <p class="doc-para">{{ t('embedDocs.subpages-text') }}</p>
    </FcSection>

    <!-- 外观参数 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.appearance-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.appearance-intro') }}</p>
      <el-table class="fc-table" :data="params" border stripe size="small">
        <el-table-column :label="t('embedDocs.th-param')" width="120">
          <template #default="{ row }"><code class="inline-code">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column :label="t('embedDocs.th-values')" min-width="280">
          <template #default="{ row }"><code class="inline-code">{{ row.values }}</code></template>
        </el-table-column>
        <el-table-column prop="desc" :label="t('embedDocs.th-desc')" min-width="200" />
      </el-table>
      <p class="doc-para">
        <span class="doc-label">{{ t('embedDocs.appearance-example') }}：</span>
        <code class="inline-code">{{ appearanceExample }}</code>
      </p>
    </FcSection>

    <!-- 认证方案 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.auth-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.auth-intro') }}</p>
      <div class="auth-cards">
        <FcSectionCard class="auth-card">
          <div class="auth-card-title">
            <FcTag color="success" solid>★</FcTag>
            {{ t('embedDocs.auth-recommended') }}
          </div>
          <p class="doc-para">{{ t('embedDocs.auth-recommended-desc') }}</p>
        </FcSectionCard>

        <FcSectionCard class="auth-card">
          <div class="auth-card-title">{{ t('embedDocs.auth-basic') }}</div>
          <p class="doc-para">{{ t('embedDocs.auth-basic-desc') }}</p>
          <el-alert type="warning" :closable="false" :title="t('embedDocs.auth-basic-warn')" show-icon />
        </FcSectionCard>
      </div>

      <h4 class="doc-sub">{{ t('embedDocs.msg-title') }}</h4>
      <el-table class="fc-table" :data="messages" border stripe size="small">
        <el-table-column :label="t('embedDocs.th-type')" width="120">
          <template #default="{ row }"><code class="inline-code">{{ row.type }}</code></template>
        </el-table-column>
        <el-table-column prop="dir" :label="t('embedDocs.th-dir')" width="140" />
        <el-table-column prop="desc" :label="t('embedDocs.th-desc')" min-width="260" />
      </el-table>
    </FcSection>

    <!-- 父页集成示例 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.parent-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.parent-intro') }}</p>
      <div class="code-block">
        <FcButton size="small" class="code-copy" @click="copyText(parentSnippet)">
          {{ t('embedDocs.copy') }}
        </FcButton>
        <pre class="code-pre"><code>{{ parentSnippet }}</code></pre>
      </div>
    </FcSection>

    <!-- Origin 白名单 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.origin-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.origin-desc') }}</p>
      <div class="code-block">
        <FcButton size="small" class="code-copy" @click="copyText(originSnippet)">
          {{ t('embedDocs.copy') }}
        </FcButton>
        <pre class="code-pre"><code>{{ originSnippet }}</code></pre>
      </div>
    </FcSection>

    <!-- 在线测试工具 -->
    <FcSection>
      <h3 class="doc-h">{{ t('embedDocs.test-title') }}</h3>
      <p class="doc-para">{{ t('embedDocs.test-desc') }}</p>
      <FcButton type="primary" @click="goTest">
        <i class="ri-external-link-line" style="margin-right: 4px" />
        {{ t('embedDocs.test-link') }}
      </FcButton>
    </FcSection>
  </div>
</template>

<script setup lang="ts">
/**
 * EmbedDocs - 平台端「嵌入接入文档」页。
 *
 * 内容基于真实实现整理 (composables/useEmbedParams.ts + useEmbedToken.ts + router):
 *   • 两套布局路由 (app 带侧栏 / page 无侧栏, 各分平台端与应用端)
 *   • 三个外观参数 (brand / mode / language, 即时生效不持久化)
 *   • 两种认证方案 (推荐级 postMessage 握手 / 基础级 URL token)
 *   • 握手消息协议 (READY / TOKEN / RENEW) 与 origin 白名单
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { FcSection, FcSectionHeader, FcSectionCard, FcTag, FcButton, toast } from '@/components/sdk'

defineOptions({ name: 'EmbedDocsPage' })

const { t } = useI18n()
const router = useRouter()

const routes = computed(() => [
  { mode: t('embedDocs.mode-platform-app'), prefix: '/benefit/platform/app', layout: t('embedDocs.layout-app'), desc: t('embedDocs.desc-platform-app') },
  { mode: t('embedDocs.mode-tenant-app'), prefix: '/benefit/tenant/app', layout: t('embedDocs.layout-app'), desc: t('embedDocs.desc-tenant-app') },
  { mode: t('embedDocs.mode-platform-page'), prefix: '/benefit/platform/page', layout: t('embedDocs.layout-page'), desc: t('embedDocs.desc-platform-page') },
  { mode: t('embedDocs.mode-tenant-page'), prefix: '/benefit/tenant/page', layout: t('embedDocs.layout-page'), desc: t('embedDocs.desc-tenant-page') },
])

// brand 白名单与 useEmbedParams.VALID_BRANDS 保持同步
const params = computed(() => [
  { name: 'brand', values: 'ldx2 | apple | google | mchuan | manyun | acme | microsoft | vonnex', desc: t('embedDocs.param-brand-desc') },
  { name: 'mode', values: 'light | dark', desc: t('embedDocs.param-mode-desc') },
  { name: 'language', values: 'zh-CN | en-US', desc: t('embedDocs.param-language-desc') },
])

const messages = computed(() => [
  { type: 'READY', dir: t('embedDocs.dir-iframe-parent'), desc: t('embedDocs.msg-ready-desc') },
  { type: 'TOKEN', dir: t('embedDocs.dir-parent-iframe'), desc: t('embedDocs.msg-token-desc') },
  { type: 'RENEW', dir: t('embedDocs.dir-iframe-parent'), desc: t('embedDocs.msg-renew-desc') },
])

const appearanceExample = '/benefit/tenant/page/dashboard?brand=apple&mode=dark&language=en-US'

const parentSnippet = `// 宿主页面 (三方前端)
const iframe = document.getElementById('benefit-iframe')

window.addEventListener('message', async (e) => {
  // ① 务必校验来源, 拒绝未知 origin
  if (e.origin !== 'https://benefit.example.com') return

  // ② 收到 READY (首次) 或 RENEW (续签) → 下发 TOKEN
  if (e.data?.type === 'READY' || e.data?.type === 'RENEW') {
    const token = await fetchBenefitToken()   // 由你的后端换取 benefit4j token
    iframe.contentWindow.postMessage(
      { type: 'TOKEN', access_token: token, expires_in: 3600 },
      e.origin,
    )
  }
})`

const originSnippet = `// frontend/src/main.ts
import { configureEmbedParentOrigins } from '@/composables/useEmbedToken'

// 生产环境仅允许受信宿主 origin (开发环境默认放行 localhost)
configureEmbedParentOrigins([
  'https://your-host.example.com',
])`

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(t('embedDocs.copied'))
  } catch {
    toast.error(t('common.error'))
  }
}

function goTest() {
  router.push('/benefit/platform/app/dev/embed-test')
}
</script>

<style scoped lang="scss">
.embed-docs {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.doc-h {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.doc-sub {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.doc-para {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--app-text-secondary, var(--el-text-color-regular));

  &:last-child { margin-bottom: 0; }
}

.doc-label {
  color: var(--app-text, var(--el-text-color-primary));
  font-weight: 500;
}

.inline-code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--app-primary, var(--el-color-primary));
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  word-break: break-all;
}

.auth-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
}

.auth-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.code-block {
  position: relative;
}

.code-copy {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}

.code-pre {
  margin: 0;
  padding: 16px;
  border-radius: 8px;
  // 固定深色编辑器背景: --el-fill-color-darker 在浅色主题下是浅灰 (#EBEDF0),
  // 会让近白文字不可见。代码块刻意不随主题翻转, 保证两种主题下都清晰可读。
  background: #1f2430;
  border: 1px solid rgba(255, 255, 255, 0.06);
  color: #e6e6f0;
  overflow-x: auto;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  line-height: 1.7;

  code {
    white-space: pre;
    color: inherit;
  }
}
</style>
