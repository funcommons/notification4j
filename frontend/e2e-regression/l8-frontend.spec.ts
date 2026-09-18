import fs from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  BASE, platformToken, createTenant, nfy, expectCode0, get, post, uniq, evidence,
  type Tenant,
} from './helpers/nfy'

/**
 * L8 前端消息中心线 · Playwright 端到端回归（UI 截图为绝对主体）
 * 覆盖：嵌入握手全链（NFY_READY/NFY_TOKEN，origin 白名单，token 只进内存）、
 *       五页导航、公告回执、铃铛未读角标、消息已读联动、撤回展示、
 *       非法 origin 拒绝、无效 token 表现、SPA fallback。
 * 对应 IT 深回归：NfyStaticPageTest + 各页数据面 IT。
 * 约定：宿主 harness = http://localhost:3000/nfy-host.html（global-setup 保证在线）；
 *       变体宿主页用 page.route + fulfill（origin 保持白名单 3000）或 page.setContent（非法 origin 场景）。
 */

const SHOTS = process.env.NFY_EVIDENCE_DIR
  ? path.resolve(process.env.NFY_EVIDENCE_DIR)
  : path.resolve(process.cwd(), '../docs/test/report/local-run/screenshots')
const HOST = 'http://localhost:3000/nfy-host.html'
const APP = (p: string) => `http://localhost:9200/nfy/tenant/app/${p}`
const BELL = 'http://localhost:9200/nfy/tenant/page/bell'
const num = (v: unknown) => Number(v)

/** 真实页面截图（相对 cwd=frontend 解析到 docs/test/report/local-run/screenshots） */
async function shot(page: Page, name: string): Promise<string> {
  fs.mkdirSync(SHOTS, { recursive: true })
  const file = path.join(SHOTS, `${name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  return file
}

const fl = (page: Page) => page.frameLocator('#nfy')

// ---------- API 造数 ----------

interface Seed { t: Tenant; uid: string }

async function seedTenant(label: string): Promise<Seed> {
  const plat = await platformToken()
  const t = await createTenant(plat, uniq(label))
  return { t, uid: uniq('u') }
}

async function createType(t: Tenant, typeCode: string, name: string): Promise<void> {
  expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/types`,
    { type_code: typeCode, name, description: 'L8 造数', default_level: 'NORMAL' }, { token: t.token })))
}

/** 发站内消息（类型须已建且启用），返回 message_id */
async function sendMessage(t: Tenant, uid: string, typeCode: string, title: string,
  level = 'NORMAL'): Promise<string> {
  const r = expectCode0(await nfy<{ message_id?: string }>(await post(`${BASE}/nfy/api/v1/runtime/messages`, {
    type_code: typeCode, level, user_ids: [uid], title, content: `${title} 的正文内容`,
  }, { token: t.token })))
  return r.data.message_id ?? ''
}

/** 建公告（DRAFT）→ 发布（PUBLISHED），返回 announcement_id */
async function publishAnnouncement(t: Tenant, title: string, needConfirm: number): Promise<string> {
  const created = expectCode0(await nfy<{ announcement_id?: string }>(await post(
    `${BASE}/nfy/api/v1/admin/announcements`, {
      title, content: `${title} 的公告正文`, level: 'NORMAL',
      effective_at: Date.now() - 60_000, expire_at: Date.now() + 86_400_000, need_confirm: needConfirm,
    }, { token: t.token })))
  const id = created.data.announcement_id ?? ''
  expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/announcements/${id}/publish`, {}, { token: t.token })))
  return id
}

/** 租户用户视角 unread-count */
async function unreadCount(t: Tenant, uid: string): Promise<{ unread: number; total: number }> {
  const r = expectCode0(await nfy<Record<string, unknown>>(await get(
    `${BASE}/nfy/api/v1/runtime/messages/unread-count`, { token: t.token, userId: uid })))
  return { unread: num(r.data.unread_count), total: num(r.data.total) }
}

test.describe('L8 前端消息中心线', () => {
  let page: Page
  test.beforeEach(async ({ browser }) => { page = await browser.newPage() })
  test.afterEach(async () => { await page.close() })

  test('L8-01 嵌入握手全链：host 页 NFY_TOKEN → 消息列表渲染种子数据', async () => {
    const { t, uid } = await seedTenant('握手租户')
    const typeCode = uniq('T')
    await createType(t, typeCode, '握手类型')
    const titleA = uniq('握手消息甲')
    const titleUrgent = uniq('握手消息乙')
    await sendMessage(t, uid, typeCode, titleA)
    await sendMessage(t, uid, typeCode, titleUrgent, 'URGENT')

    await page.goto(`${HOST}?page=messages&token=${t.token}&user_id=${uid}`)
    const f = fl(page)
    // 握手成功 → 壳渲染（侧栏仅在 connected 后出现）→ 数据列表可见
    await expect(f.locator('.nfy-brand')).toHaveText('消息中心')
    await expect(f.getByText(titleA)).toBeVisible()
    // URGENT 等级色标类 li.lv-URGENT
    await expect(f.locator('li.lv-URGENT', { hasText: titleUrgent })).toBeVisible()

    // 宿主侧观察点：token 确已投递、参数未被篡改
    const st = await page.evaluate(() => (window as unknown as { NFY_HOST_STATE: () => Record<string, unknown> }).NFY_HOST_STATE())
    expect(st.sent, '宿主已回投 NFY_TOKEN').toBe(true)
    expect(st.tokenLen, 'token 长度一致（仅经 postMessage 内存传递，不在 URL/存储）').toBe(t.token.length)
    expect(st.userId).toBe(uid)
    await shot(page, 'L8-01-嵌入握手全链消息中心')
  })

  test('L8-02 五页导航标志性元素 + SPA fallback', async () => {
    const { t, uid } = await seedTenant('导航租户')
    const typeCode = uniq('T')
    await createType(t, typeCode, '导航类型')
    await sendMessage(t, uid, typeCode, uniq('导航消息'))
    const annTitle = uniq('导航公告')
    await publishAnnouncement(t, annTitle, 0)

    await page.goto(`${HOST}?page=messages&token=${t.token}&user_id=${uid}`)
    const f = fl(page)
    const nav = (label: string) => f.locator('aside nav a', { hasText: label })

    // ① 消息页
    await expect(f.locator('.nfy-brand')).toHaveText('消息中心')
    await expect(f.getByRole('heading', { name: '消息' })).toBeVisible()
    await expect(f.getByText('未读')).toBeVisible()
    await shot(page, 'L8-02-1-消息页')
    // ② 公告页（点侧栏导航，SPA 客户端路由）
    await nav('公告').click()
    await expect(f.getByRole('heading', { name: '公告' })).toBeVisible()
    await expect(f.getByText(annTitle, { exact: true })).toBeVisible()
    await expect(nav('公告')).toHaveClass(/router-link-active/)
    await shot(page, 'L8-02-2-公告页')
    // ③ 渠道页（空态引导 + 注册按钮）
    await nav('渠道').click()
    await expect(f.getByRole('heading', { name: '我的渠道' })).toBeVisible()
    await expect(f.getByText('注册渠道').first()).toBeVisible()
    await expect(f.getByText('还没有渠道，点右上角注册')).toBeVisible()
    await shot(page, 'L8-02-3-渠道页')
    // ④ 订阅页（矩阵：类型行 + 站内信恒开列）
    await nav('订阅').click()
    await expect(f.getByRole('heading', { name: '订阅偏好' })).toBeVisible()
    await expect(f.getByText('保存')).toBeVisible()
    await expect(f.getByText('导航类型')).toBeVisible()
    await expect(f.getByText('站内信')).toBeVisible()
    await shot(page, 'L8-02-4-订阅页')
    // ⑤ 投递页（筛选器 + 空表格）
    await nav('投递').click()
    await expect(f.getByRole('heading', { name: '投递记录' })).toBeVisible()
    await expect(f.getByPlaceholder('userid')).toBeVisible()
    await expect(f.getByPlaceholder('biz_no')).toBeVisible()
    await expect(f.getByText('查询')).toBeVisible()
    await expect(f.getByText('暂无投递记录')).toBeVisible()
    await shot(page, 'L8-02-5-投递页')

    // SPA fallback：任意 /nfy/tenant/** 直返 index.html（200 + #app 挂载点）
    const r = await get(`${APP('messages')}`)
    const body = await r.text()
    expect(r.status, 'SPA fallback HTTP 200').toBe(200)
    expect(r.headers.get('content-type') ?? '').toContain('text/html')
    expect(body, '含 <div id="app"> 挂载点').toContain('<div id="app">')
    await evidence(page, 'L8-02-SPA-fallback', {
      请求: 'GET /nfy/tenant/app/messages（无静态资源命中 → fallback index.html）',
      响应: { status: r.status, content_type: r.headers.get('content-type'), 含挂载点: body.includes('<div id="app">') },
    })
  })

  test('L8-03 公告页展示与已读/确认回执（UI 操作 + API 复核）', async () => {
    const { t, uid } = await seedTenant('公告租户')
    const plainTitle = uniq('普通公告')
    const confirmTitle = uniq('需确认公告')
    const plainId = await publishAnnouncement(t, plainTitle, 0)
    const confirmId = await publishAnnouncement(t, confirmTitle, 1)

    await page.goto(`${HOST}?page=announcements&token=${t.token}&user_id=${uid}`)
    const f = fl(page)
    await expect(f.getByText(plainTitle, { exact: true })).toBeVisible()
    await expect(f.getByText(confirmTitle, { exact: true })).toBeVisible()
    // 页面同时会列出其它来源（平台级）公告，「需确认」标签断言限定在本租户公告条目内（exact 防标题/正文子串）
    await expect(f.locator('li', { hasText: confirmTitle }).getByText('需确认', { exact: true })).toBeVisible()
    await shot(page, 'L8-03-公告页初览')

    // UI 操作：普通公告「标为已读」；需确认公告「我知道了」
    await f.locator('li', { hasText: plainTitle }).getByText('标为已读').click()
    await expect(f.locator('li', { hasText: plainTitle }).getByText('标为已读')).toBeHidden()
    await f.locator('li', { hasText: confirmTitle }).getByText('我知道了').click()
    await expect(f.locator('li', { hasText: confirmTitle }).getByText('已确认')).toBeVisible()
    await shot(page, 'L8-03-公告回执操作后')

    // API 复核：runtime 列表 my_status + admin 统计 read/confirm 回执数
    const list = expectCode0(await nfy<{ list: { announcement_id: string; my_status: string }[] }>(await get(
      `${BASE}/nfy/api/v1/runtime/announcements`, { token: t.token, userId: uid })))
    const mine = Object.fromEntries(list.data.list.map((a) => [a.announcement_id, a.my_status]))
    const statsPlain = expectCode0(await nfy<Record<string, unknown>>(await get(
      `${BASE}/nfy/api/v1/admin/announcements/${plainId}/stats`, { token: t.token })))
    const statsConfirm = expectCode0(await nfy<Record<string, unknown>>(await get(
      `${BASE}/nfy/api/v1/admin/announcements/${confirmId}/stats`, { token: t.token })))
    expect(mine[plainId], '普通公告回执=READ').toBe('READ')
    expect(mine[confirmId], '需确认公告回执=CONFIRMED').toBe('CONFIRMED')
    expect(num(statsPlain.data.read_count), '普通公告已读数').toBe(1)
    expect(num(statsConfirm.data.confirm_count), '需确认公告确认数').toBe(1)
    await evidence(page, 'L8-03-公告回执API复核', {
      runtime_my_status: mine,
      普通公告stats: { read_count: statsPlain.data.read_count, confirm_count: statsPlain.data.confirm_count },
      需确认公告stats: { read_count: statsConfirm.data.read_count, confirm_count: statsConfirm.data.confirm_count },
    })
  })

  test('L8-04 铃铛未读角标 = unread-count total（bell 单页壳经真实宿主页 src 切换嵌入）', async () => {
    const { t, uid } = await seedTenant('铃铛租户')
    const typeCode = uniq('T')
    await createType(t, typeCode, '铃铛类型')
    const titles = [uniq('铃铛消息甲'), uniq('铃铛消息乙'), uniq('铃铛消息丙')]
    for (const ti of titles) await sendMessage(t, uid, typeCode, ti)

    // 复用真实宿主页（origin 3000 白名单）+ 其 replay=1 重握手参数：
    // 先按 messages 完成握手，再把 iframe src 切到铃铛单页壳（bell 无独立宿主页）
    await page.goto(`${HOST}?page=messages&token=${t.token}&user_id=${uid}&replay=1`)
    await expect(fl(page).locator('.nfy-brand')).toHaveText('消息中心')
    await page.evaluate((bell) => { (document.getElementById('nfy') as HTMLIFrameElement).src = bell }, BELL)

    await expect.poll(() => page.frames().some((fr) => fr.url().includes('/nfy/tenant/page/bell')),
      { timeout: 15000, message: '铃铛 iframe 未加载' }).toBeTruthy()
    const bellFrame = page.frames().find((fr) => fr.url().includes('/nfy/tenant/page/bell'))!
    const bell = bellFrame.locator('.nfy-bell')
    await expect(bell).toBeVisible()

    // 角标 = API unread-count.total（站内信未读 + 公告未确认；运行中平台公告可能并发新增，动态对齐）
    // 注：铃铛首拉先于握手完成（空 token 静默失败），visibilitychange 触发立即补拉
    let c = { unread: 0, total: 0 }
    await expect(async () => {
      await bellFrame.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
      const badge = await bellFrame.locator('.badge').textContent().catch(() => '')
      c = await unreadCount(t, uid)
      expect(badge ?? '', `角标(${badge}) 应等于 total(${c.total})`).toBe(String(c.total))
    }).toPass({ timeout: 20000 })
    expect(c.unread, '造数 3 条站内信未读精确计入').toBe(3)
    await shot(page, 'L8-04-铃铛未读角标')

    // 点击展开最近消息下拉（最近 5 条）
    await bell.click()
    await expect(bellFrame.getByText('最近消息')).toBeVisible()
    for (const ti of titles) await expect(bellFrame.getByText(ti)).toBeVisible()
    await expect(bellFrame.getByText('查看全部')).toBeVisible()
    await shot(page, 'L8-04-铃铛下拉最近消息')

    await evidence(page, 'L8-04-铃铛未读API复核', { unread_count: c.unread, total: c.total, 角标: String(c.total) })
  })

  test('L8-05 消息已读联动：UI 标为已读 → 角标递减 + 列表样式切换 + API 复核', async () => {
    const { t, uid } = await seedTenant('已读租户')
    const typeCode = uniq('T')
    await createType(t, typeCode, '已读类型')
    const titleA = uniq('已读消息甲')
    const titleB = uniq('已读消息乙')
    await sendMessage(t, uid, typeCode, titleA)
    await sendMessage(t, uid, typeCode, titleB)

    await page.goto(`${HOST}?page=messages&token=${t.token}&user_id=${uid}`)
    const f = fl(page)
    await expect(f.getByText(titleA)).toBeVisible()
    // 初始：未读角标 2 + 行加粗样式（li.unread）
    await expect(f.locator('.el-badge__content')).toHaveText('2')
    const rowA = f.locator('li', { hasText: titleA })
    await expect(rowA).toHaveClass(/unread/)
    await shot(page, 'L8-05-消息未读初览')

    // UI 交互：点「标为已读」→ 行样式去 unread + 角标减为 1
    await rowA.getByText('标为已读').click()
    await expect(rowA).not.toHaveClass(/unread/)
    await expect(f.locator('.el-badge__content')).toHaveText('1')
    // 已读筛选下该项仍可见
    await f.locator('.el-radio-button', { hasText: '已读' }).click()
    await expect(f.getByText(titleA)).toBeVisible()
    await shot(page, 'L8-05-消息已读联动后')

    // API 复核：unread-count 递减
    const c = await unreadCount(t, uid)
    expect(c.unread, 'API 口径未读=1').toBe(1)
    await evidence(page, 'L8-05-已读联动API复核', { unread_count: c.unread, total: c.total, UI角标: '1' })
  })

  test('L8-06 撤回展示：API 撤回已发消息 → UI 刷新后不再展示', async () => {
    const { t, uid } = await seedTenant('撤回租户')
    const typeCode = uniq('T')
    await createType(t, typeCode, '撤回类型')
    const title = uniq('将被撤回的消息')
    const mid = await sendMessage(t, uid, typeCode, title)

    await page.goto(`${HOST}?page=messages&token=${t.token}&user_id=${uid}`)
    await expect(fl(page).getByText(title)).toBeVisible()
    await shot(page, 'L8-06-撤回前消息在列')

    // API 撤回（MSG-009：SENT→CANCELLED，用户侧不可见）
    const r = expectCode0(await nfy<Record<string, unknown>>(await post(
      `${BASE}/nfy/api/v1/runtime/messages/${mid}/cancel`, {}, { token: t.token })))
    expect(r.data.status).toBe('CANCELLED')

    // UI 刷新（整页重载宿主 → 重握手）后消息消失
    await page.reload()
    const f = fl(page)
    await expect(f.locator('.nfy-brand')).toHaveText('消息中心')
    await expect(f.getByText(title)).toBeHidden()
    await expect(f.getByText('暂无消息')).toBeVisible()
    // API 复核：列表亦为空
    const list = expectCode0(await nfy<{ list: unknown[] }>(await get(
      `${BASE}/nfy/api/v1/runtime/messages`, { token: t.token, userId: uid })))
    expect(list.data.list, 'API 列表同步为空').toHaveLength(0)
    await shot(page, 'L8-06-撤回后不再展示')
    await evidence(page, 'L8-06-撤回API复核', { cancel响应: r.data, 列表条数: list.data.list.length })
  })

  test('L8-07 origin 白名单拒绝：非白名单父页投递 NFY_TOKEN 不被接受', async () => {
    const { t, uid } = await seedTenant('白名单租户')
    // 观察点：iframe 发出的任何 runtime API 调用都应缺位（无 token 可用）
    let apiCalls = 0
    page.on('request', (req) => { if (req.url().includes('/nfy/api/v1/runtime/')) apiCalls++ })

    // setContent 父页 origin=about:blank（序列化为 "null"，不在白名单 3000/5173）
    await page.setContent(`<!doctype html><html><body style="background:#fef2f2">
<h4>L8-07 变体宿主：origin=about:blank（非白名单）</h4>
<iframe id="nfy" src="${APP('messages')}" style="width:900px;height:560px;border:1px solid #fca5a5"></iframe>
<script>
window.__ready = 0; window.__sent = 0
addEventListener('message', (e) => {
  if (e.origin !== 'http://localhost:9200') return
  if (e.data && e.data.type === 'NFY_READY') {
    window.__ready++
    if (window.__sent === 0) {
      e.source.postMessage({ type: 'NFY_TOKEN', token: '${t.token}', user_id: '${uid}' }, 'http://localhost:9200')
      window.__sent = 1
    }
  }
})
</script></body></html>`)

    const f = fl(page)
    // 确认 token 已被投递（__sent=1），且 iframe 至少重发 3 次 READY（>1s）仍停留在 waiting
    await expect.poll(() => page.evaluate(() => (window as unknown as { __sent: number }).__sent), { timeout: 15000 })
      .toBe(1)
    await expect.poll(() => page.evaluate(() => (window as unknown as { __ready: number }).__ready), { timeout: 15000 })
      .toBeGreaterThanOrEqual(3)
    // 契约：白名单外消息一律忽略 → 永远「正在连接…」，壳与数据不渲染
    await expect(f.getByText('正在连接…')).toBeVisible()
    await expect(f.locator('aside.nfy-side')).toHaveCount(0)
    expect(apiCalls, '无任何 runtime API 调用发出（token 未进内存）').toBe(0)
    await shot(page, 'L8-07-非白名单origin拒绝')
    await evidence(page, 'L8-07-白名单拒绝观察点', {
      父页origin: 'about:blank（null origin，不在 http://localhost:5173,http://localhost:3000 白名单）',
      NFY_TOKEN已投递: true, NFY_READY重发次数: '≥3（≥1s 仍 waiting）',
      iframe运行时API调用数: apiCalls, 结论: 'token 未进内存，握手未完成，壳保持「正在连接…」',
    })
  })

  test('L8-08 无效 token 表现：数据面鉴权失败 → 会话失效重连提示（F-2 修复后行为）', async () => {
    const { uid } = await seedTenant('无效token租户')
    const badToken = `invalid.${uniq('tok')}.sig`

    // 钉死后端对无效 token 的信封形态：HTTP 200 + 认证类 code=10202（fwk ApiCode 102xx 段）
    const api = await nfy<unknown>(await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`,
      { token: badToken, userId: uid }))
    expect(api.status, '认证失败统一 HTTP 200 信封（契约 §2.2）').toBe(200)
    expect(api.code, '无效签名 token → 信封 code=10202（鉴权类）').toBe(10202)

    await page.goto(`${HOST}?page=messages&token=${badToken}&user_id=${uid}`)
    const f = fl(page)
    // 无效 token 亦为非空字符串 → 握手层先 connected；数据请求信封 10202 →
    // client.onAuthExpired → handshake.notifyExpired() 回 waiting 重握手。
    // 修复后（F-2）：呈现会话失效/重连连接态，宿主不再重发 token → 停留在重连提示
    await expect(f.getByText('会话已失效，正在重新连接…')).toBeVisible({ timeout: 15000 })
    // 不再出现误导性「暂无消息」空态；侧栏与数据面均不可见
    await expect(f.getByText('暂无消息')).toHaveCount(0)
    await expect(f.locator('aside.nfy-side')).toHaveCount(0)
    await shot(page, 'L8-08-无效token会话失效重连提示')
    await evidence(page, 'L8-08-无效tokenAPI表现', {
      无效token响应: { http_status: api.status, code: api.code, message: api.message },
      UI表现: 'client 鉴权失败回调 → notifyExpired 回 waiting → 壳呈现「会话已失效，正在重新连接…」，无「暂无消息」空态（F-2 修复）',
    })
  })
})
