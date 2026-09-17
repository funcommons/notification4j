import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { computed, defineComponent, h, provide, ref } from 'vue'
import MessagesPage from './MessagesPage.vue'
import { NFY_KEY, type NfyContext } from '@/api/nfy/nfyContext'
import { NfyApiError } from '@/api/nfy/client'
import { createEmbedHandshake } from '@/composables/nfy/useEmbedHandshake'
import type { MessageListResult } from '@/api/nfy'

// VECTOR: TAG=f8-invalid-date / f2-auth-empty-state
// 组件级回归（useNfy 经 NFY_KEY 注入桩上下文，apis 走 vi.fn 桩）：
// - F-1：created_at 为 Long→String 数字字符串（"1789654352932"）时渲染有效时间，
//   不得出现 Invalid Date（缺陷根因：new Date(数字字符串) → Invalid Date）；
// - F-2：会话失效（authError 置位）时显示重连提示而非「暂无消息」空态。

function makeCtx(opts: { listResult?: MessageListResult; authError?: NfyApiError | null } = {}): NfyContext {
  const list: MessageListResult = opts.listResult ?? { list: [], next_cursor: null, has_more: false }
  return {
    handshake: createEmbedHandshake({ allowedOrigins: ['https://t.example'], isEmbedded: () => false }),
    authError: ref(opts.authError ?? null),
    ready: computed(() => true),
    apis: {
      messages: {
        list: vi.fn().mockResolvedValue(list),
        unreadCount: vi.fn().mockResolvedValue({ unread_count: 0, unconfirmed_count: 0, total: 0 }),
        markRead: vi.fn().mockResolvedValue({ read_count: 0 }),
      },
    } as unknown as NfyContext['apis'],
  }
}

function mountWithCtx(ctx: NfyContext) {
  const Host = defineComponent({
    setup() {
      provide(NFY_KEY, ctx)
      return () => h(MessagesPage)
    },
  })
  return mount(Host)
}

const numericStringItem = {
  message_id: 'm1',
  title: '雪花消息',
  type_code: 'SYS_NOTICE',
  level: 'NORMAL',
  read_status: 'UNREAD',
  created_at: '1789654352932', // Long→String：数字字符串
}

describe('MessagesPage', () => {
  it('F-1: created_at 数字字符串渲染有效时间（无 Invalid Date）', async () => {
    const ctx = makeCtx({ listResult: { list: [numericStringItem], next_cursor: null, has_more: false } })
    const wrapper = mountWithCtx(ctx)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('雪花消息')
    expect(text, '不得出现 Invalid Date').not.toContain('Invalid Date')
    expect(text, '数字字符串应按毫秒时间戳正确格式化').toContain(new Date(1789654352932).toLocaleString())
  })

  it('F-2: authError 置位 → 显示会话失效提示而非「暂无消息」', async () => {
    const ctx = makeCtx({ authError: new NfyApiError(10202, '登录凭证无效') })
    const wrapper = mountWithCtx(ctx)
    await flushPromises()
    expect(wrapper.text()).toContain('会话已失效，正在重新连接…')
    expect(wrapper.text()).not.toContain('暂无消息')
  })

  it('F-2: authError 为 null → 维持「暂无消息」空态', async () => {
    const wrapper = mountWithCtx(makeCtx())
    await flushPromises()
    expect(wrapper.text()).toContain('暂无消息')
    expect(wrapper.text()).not.toContain('会话已失效')
  })
})
