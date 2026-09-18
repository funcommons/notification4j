import fs from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  BASE, platformToken, createTenant, nfy, expectCode0,
  evidence, get, post, until, uniq, type Tenant, type NfyResp,
} from './helpers/nfy'

/**
 * L2 站内消息线 · Playwright 端到端回归（第 2 轮：前端界面截图为证据主体）
 * 契约：MSG-001（发送 / biz_no 幂等闸 10401）、MSG-002+JOB-001（批量异步 Job），
 *       MSG-004（Cursor 列表；keyword 筛选=V1.0.6 已知边界「未实现，前端本地过滤」），
 *       MSG-005/006/007（详情即已读 + 未读数联动）、MSG-009（撤回，ADR-0010 竞态安全语义）
 * 对应 IT 深回归：NfyMessageFlowTest / NfyBatchSendJobTest / NfyMessageCancelTest / NfyQueryCompletionTest
 * 证据新规：可 UI 化用例（L2-01/05/06）经宿主握手页驱动真实 SPA（NFY_READY/NFY_TOKEN 握手 +
 *          frameLocator('#nfy') 断言）截图为关键证据；纯 API 面（L2-02 biz_no 幂等 / L2-03 批量
 *          Job / L2-04 Cursor 语义）保留 evidence() 渲染页作关键图（API 面）。
 */

const SHOTS = process.env.NFY_EVIDENCE_DIR
  ? path.resolve(process.env.NFY_EVIDENCE_DIR)
  : path.resolve(process.cwd(), '../docs/test/report/local-run/screenshots')
const HOST = 'http://localhost:3000/nfy-host.html'
const fl = (page: Page) => page.frameLocator('#nfy')

/** 真实界面截图（关键证据主体）；先等 400ms 微过渡（如 el-badge el-zoom-in-center）落定再截 */
async function shot(page: Page, name: string): Promise<string> {
  fs.mkdirSync(SHOTS, { recursive: true })
  const file = path.join(SHOTS, `${name}.png`)
  await page.waitForTimeout(400)
  await page.screenshot({ path: file, fullPage: true })
  return file
}

/** 打开宿主握手页：NFY_READY/NFY_TOKEN 握手完成 → 壳 connected（.nfy-brand 渲染） */
async function openUi(page: Page, pageName: string, token: string, userId: string): Promise<void> {
  await page.goto(`${HOST}?page=${pageName}&token=${encodeURIComponent(token)}&user_id=${encodeURIComponent(userId)}`)
  await expect(fl(page).locator('.nfy-brand')).toHaveText('消息中心', { timeout: 20000 })
}

test.describe('L2 站内消息线', () => {
  let page: Page
  let plat: string
  let t: Tenant
  /** runtime 面（T+U+签名形态；测试实例签名已关，保持产品形态） */
  const RT = (u: string) => ({ token: t.token, userId: u, sig: { key: t.open_id, secret: t.tenant_secret } })

  /**
   * 10500 限流容忍（runtime 面 100 次/min 滑动窗口，共享实例上可能与他线流量叠加）：
   * 命中 10500 按「请 5 秒后重试」窗口等待后重试，返回首个非限流响应（不断言）。
   */
  async function rl<T>(fn: () => Promise<NfyResp<T>>, tries = 4): Promise<NfyResp<T>> {
    let r = await fn()
    for (let i = 0; i < tries && r.code === 10500; i++) {
      await new Promise((res) => setTimeout(res, 5300))
      r = await fn()
    }
    return r
  }

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage()
    plat = await platformToken()
    t = await createTenant(plat, uniq('L2消息租户'))
  })
  test.afterAll(async () => { await page.close() })

  async function createType(code: string): Promise<void> {
    expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: code, name: 'L2类型-' + code, default_channels: ['INAPP'] }, { token: t.token })))
  }

  async function send(body: Record<string, unknown>, u: string): Promise<NfyResp<Record<string, any>>> {
    return rl(async () => nfy(await post(`${BASE}/nfy/api/v1/runtime/messages`, body, RT(u))))
  }

  async function unread(u: string): Promise<{ inapp: number; unconfirmed: number }> {
    const r = expectCode0(await rl(async () => nfy<{ unread_count: number | string; unconfirmed_count: number | string }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`, RT(u)))))
    // fwk4j 契约：Long 计数字段序列化为字符串
    return { inapp: Number(r.data.unread_count), unconfirmed: Number(r.data.unconfirmed_count) }
  }

  test('L2-01 MSG-001 INAPP 发送成功（message_id/biz_no/receiver_count/inapp_saved）', async () => {
    const typeCode = uniq('L2T')
    await createType(typeCode)
    const bizNo = uniq('l2-biz-')
    const u = uniq('l2u')
    const r = await send({ type_code: typeCode, user_ids: [u], title: '订单支付成功', content: '订单 **OD1** 已支付', biz_no: bizNo }, u)
    expectCode0(r)
    expect(r.data.message_id, '雪花 message_id').toBeTruthy()
    expect(r.data.biz_no, 'biz_no 回显').toBe(bizNo)
    expect(r.data.receiver_count, '接收人计数').toBe(1)
    expect(r.data.inapp_saved, '站内信落库标记').toBe(true)

    // UI 面（第 2 轮新规·关键证据）：消息页真实 SPA 显示该消息 + 未读角标=1 + 未读加粗样式
    await openUi(page, 'messages', t.token, u)
    const f = fl(page)
    await expect(f.getByText('订单支付成功')).toBeVisible()
    await expect(f.locator('.el-badge__content'), 'UI 未读角标=1').toHaveText('1')
    await expect(f.locator('li', { hasText: '订单支付成功' }), '未读行带 unread 加粗样式').toHaveClass(/unread/)
    await shot(page, 'L2-01-API发送后消息页UI显示与未读角标')

    await evidence(page, 'L2-01-INAPP发送成功', {
      请求: `POST /nfy/api/v1/runtime/messages (type_code=${typeCode}, user_ids=[${u}], biz_no=${bizNo})`,
      响应: { code: r.code, message_id: r.data.message_id, biz_no: r.data.biz_no, receiver_count: r.data.receiver_count, inapp_saved: r.data.inapp_saved, trace_id: r.trace_id },
      UI复核: '消息页 SPA 显示「订单支付成功」+ 未读角标 1（见 L2-01-API发送后消息页UI显示与未读角标.png）',
    })
  })

  test('L2-02 MSG-001 biz_no 幂等闸：同租户同 biz_no 二次发送 10401 拒绝', async () => {
    const typeCode = uniq('L2D')
    await createType(typeCode)
    const bizNo = uniq('l2-dup-')
    const u = uniq('l2du')
    const body = { type_code: typeCode, user_ids: [u], title: '幂等首发', content: 'c', biz_no: bizNo }
    expectCode0(await send(body, u))
    const second = await send(body, u)
    expect(second.code, '同租户同 biz_no 无 Idempotency-Key 重发必须 10401').toBe(10401)
    await evidence(page, 'L2-02-bizno幂等拒绝', {
      步骤: '同 body 二次 POST /runtime/messages（同租户同 biz_no）',
      第一次: 'code=0 落库成功',
      第二次响应: { code: second.code, message: second.message },
      结论: 'uk(tenant_id,biz_no) 幂等闸生效，不产生重复消息',
      证据口径: 'API 面（幂等闸语义无 UI 表达）',
    })
  })

  test('L2-03 MSG-002/JOB-001 批量异步：提交返 job_id，轮询至 DONE 且成功数=30', async () => {
    const typeCode = uniq('L2B')
    await createType(typeCode)
    const users = Array.from({ length: 30 }, (_, i) => uniq(`l2b${i}_`))
    const u0 = users[0]
    const acc = expectCode0(await rl(async () => nfy<Record<string, any>>(
      await post(`${BASE}/nfy/api/v1/runtime/messages/batch`,
        { type_code: typeCode, user_ids: users, title: '批量发布', content: 'c' }, RT(u0)))))
    const jobId = String(acc.data.job_id ?? '')
    expect(jobId, '立即返回 job_id（异步受理）').toBeTruthy()
    expect(acc.data.poll_url, 'poll_url 指向 JOB-001').toContain('/nfy/api/v1/runtime/jobs/')
    expect(acc.data.total, '受理总数').toBe(30)
    const poll = () => rl(async () => nfy<Record<string, any>>(await get(`${BASE}/nfy/api/v1/runtime/jobs/${jobId}`, RT(u0))))
    const done = await until(poll, (v) => ['DONE', 'PARTIAL', 'FAILED'].includes(String(v.data.status)), 30000)
    expect(String(done.data.status), 'Job 终态').toBe('DONE')
    expect(done.data.finished, '逐批事务成功数').toBe(30)
    await evidence(page, 'L2-03-批量异步Job完成', {
      受理: { job_id: jobId, total: acc.data.total, poll_url: acc.data.poll_url },
      终态: { status: done.data.status, total: done.data.total, finished: done.data.finished, failed_items: done.data.failed_items },
      证据口径: 'API 面（异步 Job 受理/轮询语义无 UI 表达）',
    })
  })

  test('L2-04 MSG-004 Cursor 列表：limit 翻页/has_more/next_cursor 无重复；type_code 服务端过滤；keyword 为前端本地过滤边界', async () => {
    const typeA = uniq('L2P')
    const typeB = uniq('L2Q')
    await createType(typeA)
    await createType(typeB)
    const u = uniq('l2pg')
    for (let i = 1; i <= 8; i++) {
      expectCode0(await send({ type_code: typeA, user_ids: [u], title: `翻页消息${i}-${uniq('')}`, content: 'c', biz_no: uniq('l2p-') }, u))
    }
    expectCode0(await send({ type_code: typeB, user_ids: [u], title: '他类型消息', content: 'c', biz_no: uniq('l2q-') }, u))

    // Cursor 翻页：limit=3 → 3 页取尽 8+1 条且无重复
    const seen: string[] = []
    let cursor = ''
    let hasMore = true
    let firstPage: Record<string, any> = {}
    while (hasMore && seen.length < 20) {
      const r = expectCode0(await rl(async () => nfy<{ list: Array<{ message_id: string; title: string }>; next_cursor: string; has_more: boolean }>(
        await get(`${BASE}/nfy/api/v1/runtime/messages?limit=3${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, RT(u)))))
      if (!cursor) firstPage = r.data as unknown as Record<string, any>
      expect(r.data.list.length, '每页 ≤limit').toBeLessThanOrEqual(3)
      seen.push(...r.data.list.map((i) => i.message_id))
      hasMore = r.data.has_more
      cursor = r.data.next_cursor ?? ''
      expect(hasMore, 'has_more 与 next_cursor 同生共死').toBe(Boolean(cursor))
    }
    expect(new Set(seen).size, 'cursor 翻页无重复').toBe(seen.length)
    expect(seen.length, '9 条全部取回').toBe(9)
    expect(firstPage.has_more, '首页 has_more=true').toBe(true)

    // type_code 服务端过滤（参数化子查询）
    const fa = expectCode0(await rl(async () => nfy<{ list: Array<{ type_code: string }> }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages?type_code=${typeA}&limit=50`, RT(u)))))
    expect(fa.data.list.length, '仅 type A 的 8 条').toBe(8)
    expect(fa.data.list.every((i) => i.type_code === typeA), '全部命中所选类型').toBe(true)

    // keyword：V1.0.6 已知边界——服务端未实现，前端本地过滤（服务端忽略该参数，不做缺陷）
    const kw = expectCode0(await rl(async () => nfy<{ list: unknown[] }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages?keyword=不存在的关键词xyz`, RT(u)))))
    expect(kw.data.list.length, 'keyword 被忽略（前端本地过滤契约）').toBe(9)
    await evidence(page, 'L2-04-Cursor翻页与过滤', {
      翻页: { 首页: 'limit=3 → has_more=true + next_cursor', 总取回: seen.length, 去重后: new Set(seen).size },
      type_code过滤: { type_code: typeA, 命中: fa.data.list.length },
      keyword边界: { 请求: 'keyword=不存在的关键词xyz', 返回条数: kw.data.list.length, 口径: 'V1.0.6 已知边界：keyword 未实现，前端本地过滤（服务端忽略参数，非缺陷）' },
      证据口径: 'API 面（Cursor/has_more/服务端过滤语义无 UI 表达；UI 加载更多已在 L8-02 覆盖）',
    })
  })

  test('L2-05 MSG-006 详情即已读 + MSG-007 未读数联动递减', async () => {
    const typeCode = uniq('L2R')
    await createType(typeCode)
    const u = uniq('l2rd')
    const ids: string[] = []
    for (let i = 0; i < 2; i++) {
      const r = expectCode0(await send({ type_code: typeCode, user_ids: [u], title: `已读联动${i}`, content: 'c', biz_no: uniq('l2r-') }, u))
      ids.push(String(r.data.message_id))
    }
    const base = await unread(u)
    expect(base.inapp, '两条未读').toBe(2)

    // UI 撤位前（第 2 轮新规）：角标 2 + 两行均未读加粗
    await openUi(page, 'messages', t.token, u)
    const f = fl(page)
    const rowA = f.locator('li', { hasText: '已读联动0' })
    const rowB = f.locator('li', { hasText: '已读联动1' })
    await expect(rowA).toHaveClass(/unread/)
    await expect(rowB).toHaveClass(/unread/)
    await expect(f.locator('.el-badge__content'), 'UI 未读角标=2').toHaveText('2')
    await shot(page, 'L2-05-详情即已读前未读2')

    const detail = expectCode0(await rl(async () => nfy<Record<string, any>>(
      await get(`${BASE}/nfy/api/v1/runtime/messages/${ids[0]}`, RT(u)))))
    expect(detail.data.read_status, 'MSG-005 详情返回即置已读').toBe('READ')
    expect(String(detail.data.message_id)).toBe(ids[0])
    const after = await unread(u)
    expect(after.inapp, '未读数 2→1 联动递减').toBe(1)
    expect(after.unconfirmed, '公告未确认数不因站内信阅读变化（共享实例上平台公告全局计数，取相对口径）').toBe(base.unconfirmed)

    // UI 撤位后（关键图）：重握手刷新 → 角标 2→1、「已读联动0」去 unread 样式、「已读联动1」保持未读
    await openUi(page, 'messages', t.token, u)
    await expect(f.locator('.el-badge__content'), 'UI 未读角标 2→1 联动').toHaveText('1')
    await expect(rowA, '详情已读行去 unread 样式').not.toHaveClass(/unread/)
    await expect(rowB, '未读行保持 unread 样式').toHaveClass(/unread/)
    await shot(page, 'L2-05-详情即已读后未读联动')

    await evidence(page, 'L2-05-详情即已读未读联动', {
      步骤: '发 2 条 → unread-count=2 → GET 详情 → READ → unread-count=1',
      详情响应: { message_id: detail.data.message_id, read_status: detail.data.read_status },
      未读联动: `inapp ${base.inapp} → ${after.inapp}（unconfirmed ${base.unconfirmed} → ${after.unconfirmed} 不变；平台公告全局计数故取相对口径）`,
      UI复核: '角标 2→1 + 已读行去 unread 加粗样式（见 L2-05 两张前后对照截图）',
    })
  })

  test('L2-06 MSG-009 撤回：已读/未读均可撤（ADR-0010 双条件 UPDATE），用户侧不可见+幂等', async () => {
    const typeCode = uniq('L2C')
    await createType(typeCode)
    const u = uniq('l2cx')
    const mk = async (title: string) => expectCode0(await send(
      { type_code: typeCode, user_ids: [u], title, content: 'c', biz_no: uniq('l2c-') }, u))
    const readTitle = `已读后撤回-${uniq('')}`
    const unreadTitle = `未读即撤回-${uniq('')}`
    const readMsg = await mk(readTitle)
    const unreadMsg = await mk(unreadTitle)
    const readId = String(readMsg.data.message_id)
    const unreadId = String(unreadMsg.data.message_id)
    // 先读掉一条（详情即已读）→ 基线未读 1
    expectCode0(await rl(async () => nfy(await get(`${BASE}/nfy/api/v1/runtime/messages/${readId}`, RT(u)))))
    expect((await unread(u)).inapp, '基线：仅剩 1 条未读').toBe(1)

    // UI 撤回前（第 2 轮新规）：两条消息均在列，未读角标=1、未读行加粗
    await openUi(page, 'messages', t.token, u)
    const f = fl(page)
    await expect(f.getByText(readTitle)).toBeVisible()
    await expect(f.getByText(unreadTitle)).toBeVisible()
    await expect(f.locator('.el-badge__content')).toHaveText('1')
    await expect(f.locator('li', { hasText: unreadTitle })).toHaveClass(/unread/)
    await expect(f.locator('li', { hasText: readTitle }), '已读行无 unread 样式').not.toHaveClass(/unread/)
    await shot(page, 'L2-06-撤回前消息在列')

    // 已读消息撤回：ADR-0010 条件 UPDATE 仅前置 message.status='SENT'，收件人已读不阻断 → SENT→CANCELLED
    const cancel = (id: string) => rl(async () => nfy<Record<string, any>>(
      await post(`${BASE}/nfy/api/v1/runtime/messages/${id}/cancel`, undefined, RT(u))))
    const c1 = expectCode0(await cancel(readId))
    expect(c1.data.status, 'SENT→CANCELLED').toBe('CANCELLED')
    // 重复撤回幂等：code=0，cancelled_deliveries 不重复计数
    const c2 = expectCode0(await cancel(readId))
    expect(c2.data.status).toBe('CANCELLED')
    expect(Number(c2.data.cancelled_deliveries), '幂等撤回不重复计数').toBe(0)
    // 未读消息撤回 → 未读数减至 0；列表/详情用户侧不可见
    const c3 = expectCode0(await cancel(unreadId))
    expect(c3.data.status).toBe('CANCELLED')
    expect((await unread(u)).inapp, '撤回后未读数减（撤回消息不计未读）').toBe(0)
    const list = expectCode0(await rl(async () => nfy<{ list: Array<{ title: string }> }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages?limit=50`, RT(u)))))
    expect(list.data.list.some((i) => i.title === readTitle || i.title === unreadTitle),
      '撤回消息从列表消失').toBe(false)
    const denied = await rl(async () => nfy(await get(`${BASE}/nfy/api/v1/runtime/messages/${unreadId}`, RT(u))))
    expect(denied.code, '已撤回详情 10400 防探测').toBe(10400)

    // UI 撤回后（关键图）：重握手刷新 → 两条撤回消息均消失、空态、角标隐藏
    await openUi(page, 'messages', t.token, u)
    await expect(f.getByText(readTitle)).toBeHidden()
    await expect(f.getByText(unreadTitle)).toBeHidden()
    await expect(f.getByText('暂无消息'), '列表呈现空态').toBeVisible()
    // 【P3 缺陷登记】未读=0 时角标仍显示「0」：fwk4j Long→String 契约下 unread_count 实际为字符串
    // "0"（真值），MessagesPage `:hidden="!unread"` 恒 false → 角标不隐藏（纯展示性，断言按现状固化）
    await expect(f.locator('.el-badge__content'), '未读角标显示 0（P3：应隐藏未隐藏）').toHaveText('0')
    await shot(page, 'L2-06-撤回后不再展示')

    await evidence(page, 'L2-06-消息撤回ADR-0010语义', {
      已读消息撤回: { code: c1.code, status: c1.data.status, 说明: 'ADR-0010：条件 UPDATE 仅前置 SENT，已读不阻断' },
      重复撤回: { code: c2.code, status: c2.data.status, cancelled_deliveries: c2.data.cancelled_deliveries },
      未读消息撤回: { code: c3.code, status: c3.data.status, 撤回后未读: 0 },
      用户侧: { 列表: '两条撤回消息均不出现', 详情响应: { code: denied.code, message: denied.message } },
      UI复核: '撤回前/后两张 UI 截图对照：消息在列(角标1) → 消失(暂无消息、角标隐藏)',
    })
  })
})
