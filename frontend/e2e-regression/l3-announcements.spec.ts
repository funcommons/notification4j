import fs from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  BASE, platformToken, createTenant, nfy, expectCode0,
  evidence, get, post, uniq, type Tenant, type NfyResp,
} from './helpers/nfy'

/**
 * L3 公告线 · Playwright 端到端回归（第 2 轮：前端界面截图为证据主体）
 * 契约：ANN-001（平台+租户合并生效列表，published_at 倒序）、ANN-002（标记阅读幂等）、
 *       ANN-003（确认幂等 + unconfirmed_count 联动）、AAN-001~005（租户公告管理）、
 *       PAN-001~004（平台公告；第 28 步修复回归：confirm 回执落公告归属域 → 平台侧 confirm_count 可见）、
 *       PTE-005（mandatory 唯一入口=平台域）
 * 对应 IT 深回归：NfyAnnouncementFlowTest / NfyAnnouncementAdminTest / NfyPlatformDomainTest
 * 证据新规：可 UI 化用例（L3-01 合并列表 / L3-02 标为已读 / L3-03 我知道了确认）经宿主握手页驱动
 *          真实 SPA（NFY_READY/NFY_TOKEN 握手 + frameLocator('#nfy') 断言）截图为关键证据；
 *          纯 API 面（L3-04 管理生命周期 / L3-05 PAN 跨域 confirm_count / L3-06 mandatory）保留
 *          evidence() 渲染页作关键图（API 面）。
 */

const SHOTS = process.env.NFY_EVIDENCE_DIR
  ? path.resolve(process.env.NFY_EVIDENCE_DIR)
  : path.resolve(process.cwd(), '../docs/test/report/local-run/screenshots')
const HOST = 'http://localhost:3000/nfy-host.html'
const fl = (page: Page) => page.frameLocator('#nfy')

/** 真实界面截图（关键证据主体）；先等 400ms 微过渡（如 el-zoom-in-center）落定再截 */
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

test.describe('L3 公告线', () => {
  let page: Page
  let plat: string
  let tA: Tenant
  let tB: Tenant
  const platBase = `${BASE}/nfy/platform/api/v1/announcements`
  const tenBase = `${BASE}/nfy/api/v1/admin/announcements`
  const RT = (t: Tenant, u: string) => ({ token: t.token, userId: u, sig: { key: t.open_id, secret: t.tenant_secret } })

  /** 10500 限流容忍（runtime 面 100 次/min；返回首个非限流响应，不断言） */
  async function rl<T>(fn: () => Promise<NfyResp<T>>, tries = 4): Promise<NfyResp<T>> {
    let r = await fn()
    for (let i = 0; i < tries && r.code === 10500; i++) {
      await new Promise((res) => setTimeout(res, 5300))
      r = await fn()
    }
    return r
  }

  /** 本线自有平台公告标题前缀（历史运行遗留会占 tenant 0 的 20 条生效配额） */
  const MY_PLAT_PREFIXES = ['平台维护通知-', '阅读幂等公告-', '确认联动公告-', 'PAN确认域回归-', 'curl-PAN-detail-probe']

  /** 清理本线历史遗留的已发布平台公告（仅下线自有前缀，不动他人数据），释放 tenant 0 配额 */
  async function cleanupMyPlatformLeftovers(): Promise<number> {
    let offlined = 0
    for (let offset = 0; offset < 400; offset += 50) {
      const r = await nfy<{ list?: Array<Record<string, any>> }>(
        await get(`${platBase}?status=PUBLISHED&offset=${offset}&limit=50`, { token: plat }))
      if (r.code !== 0) break
      const mine = (r.data.list ?? []).filter((a) => MY_PLAT_PREFIXES.some((p) => String(a.title).startsWith(p)))
      for (const a of mine) {
        const off = await nfy(await post(`${platBase}/${a.announcement_id}/offline`, undefined, { token: plat }))
        if (off.code === 0) offlined++
      }
      if (!r.data.list || r.data.list.length < 50) break
    }
    return offlined
  }

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage()
    plat = await platformToken()
    await cleanupMyPlatformLeftovers()
    tA = await createTenant(plat, uniq('L3公告租户A'))
    tB = await createTenant(plat, uniq('L3公告租户B'))
  })
  test.afterAll(async () => { await page.close() })

  /**
   * 建公告（platform 域传 plat token；tenant 域传租户 token）→ 返回 announcement_id。
   * 生效窗口=1h 前 ~ 20min 后（短时效，避免累积占满 20 条同时生效配额触发 10611）。
   */
  async function createAnnouncement(token: string, base: string, p: {
    title: string; needConfirm?: number; bizNo?: string; publish?: boolean; effPlusHours?: number
  }): Promise<string> {
    const body = {
      title: p.title, content: `内容-${p.title}`, level: 'IMPORTANT',
      effective_at: Date.now() + (p.effPlusHours ?? -1) * 3600_000,
      expire_at: Date.now() + 20 * 60_000,
      need_confirm: p.needConfirm ?? 0, biz_no: p.bizNo ?? uniq('l3ann-'),
    }
    const r = expectCode0(await nfy(await post(`${base}`, body, { token })))
    const id = String(r.data.announcement_id ?? '')
    expect(id, '创建返回 announcement_id').toBeTruthy()
    expect(r.data.status, '创建即 DRAFT').toBe('DRAFT')
    if (p.publish) {
      expectCode0(await nfy(await post(`${base}/${id}/publish`, undefined, { token })))
    }
    return id
  }

  async function runtimeList(t: Tenant, u: string): Promise<NfyResp<{ list: Array<Record<string, any>>; has_more: boolean }>> {
    return expectCode0(await rl(async () => nfy(
      await get(`${BASE}/nfy/api/v1/runtime/announcements?limit=50`, RT(t, u)))))
  }

  async function unconfirmed(t: Tenant, u: string): Promise<number> {
    const r = expectCode0(await rl(async () => nfy<{ unconfirmed_count: number | string }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`, RT(t, u)))))
    return Number(r.data.unconfirmed_count) // fwk4j 契约：Long 计数字段序列化为字符串
  }

  test('L3-01 ANN-001 平台公告(tenant 0)+租户公告合并生效列表，published_at 倒序，草稿不可见', async () => {
    const platTitle = `平台维护通知-${uniq('')}`
    const tenTitle = `租户系统上线-${uniq('')}`
    const draftTitle = `草稿不可见-${uniq('')}`
    await createAnnouncement(plat, platBase, { title: platTitle, needConfirm: 1, publish: true })
    // 租户公告后发布（published_at 更晚 → 倒序在前）
    await createAnnouncement(tA.token, tenBase, { title: tenTitle, publish: true })
    await createAnnouncement(tA.token, tenBase, { title: draftTitle, publish: false })
    const uidA = uniq('l3u')
    const r = await runtimeList(tA, uidA)
    const titles = r.data.list.map((i) => String(i.title))
    expect(titles.some((x) => x === platTitle), '平台公告(tenant 0)同页可见').toBe(true)
    expect(titles.some((x) => x === tenTitle), '本租户生效公告可见').toBe(true)
    expect(titles.some((x) => x === draftTitle), 'DRAFT 不可见').toBe(false)
    expect(titles.indexOf(tenTitle), 'published_at 倒序：租户(后发布)在前').toBeLessThan(titles.indexOf(platTitle))
    const platItem = r.data.list.find((i) => i.title === platTitle)!
    expect(platItem.my_status, '初始 my_status=NONE').toBe('NONE')
    expect(platItem.need_confirm, 'need_confirm 透出').toBe(1)
    // 跨租户：B 只看平台公告
    const rb = await runtimeList(tB, uniq('l3u'))
    expect(rb.data.list.some((i) => i.title === platTitle), 'B 可见平台公告').toBe(true)
    expect(rb.data.list.some((i) => i.title === tenTitle), 'B 不可见 A 租户公告').toBe(false)

    // UI 面（第 2 轮新规·关键证据）：公告页真实 SPA 呈现合并列表（平台/需确认标签 + 倒序 + 草稿不可见）
    await openUi(page, 'announcements', tA.token, uidA)
    const f = fl(page)
    await expect(f.getByRole('heading', { name: '公告' })).toBeVisible()
    await expect(f.getByText(platTitle, { exact: true })).toBeVisible()
    await expect(f.getByText(tenTitle, { exact: true })).toBeVisible()
    await expect(f.locator('li', { hasText: platTitle }).getByText('平台', { exact: true }),
      '平台公告带「平台」标签').toBeVisible()
    await expect(f.locator('li', { hasText: platTitle }).getByText('需确认', { exact: true }),
      'need_confirm=1 带「需确认」标签').toBeVisible()
    await expect(f.getByText(draftTitle), 'DRAFT 草稿 UI 不可见').toHaveCount(0)
    const tenY = (await f.locator('li', { hasText: tenTitle }).boundingBox())!.y
    const platY = (await f.locator('li', { hasText: platTitle }).boundingBox())!.y
    expect(tenY, 'UI 列表 published_at 倒序：租户公告(后发布)在平台公告上方').toBeLessThan(platY)
    await shot(page, 'L3-01-公告页UI合并列表')

    await evidence(page, 'L3-01-合并生效列表', {
      断言: { 平台公告: platTitle, 租户公告: tenTitle, 草稿: draftTitle + '(不可见)' },
      排序: 'published_at 倒序 → 租户公告(后发布)在前',
      A列表条数: r.data.list.length, B可见平台公告: true, B不可见A租户公告: true,
      UI复核: '公告页 SPA 同屏呈现平台+租户公告、平台/需确认标签、倒序（见 L3-01-公告页UI合并列表.png）',
    })
  })

  test('L3-02 ANN-002 markRead 两次调用幂等，my_status→READ，unconfirmed 不因阅读变化', async () => {
    const title = `阅读幂等公告-${uniq('')}`
    const id = await createAnnouncement(plat, platBase, { title, needConfirm: 1, publish: true })
    const u = uniq('l3ru')
    const before = await unconfirmed(tA, u)

    // 第 1 次 markRead 经真实 SPA 控件「标为已读」（UI 面·关键证据）
    await openUi(page, 'announcements', tA.token, u)
    const f = fl(page)
    const row = f.locator('li', { hasText: title })
    await expect(row.getByText('标为已读')).toBeVisible()
    await row.getByText('标为已读').click()
    await expect(row.getByText('标为已读'), '已读后「标为已读」控件消失').toBeHidden()
    await shot(page, 'L3-02-UI标为已读操作后')

    // 第 2 次 markRead 走 API → 两次调用均成功（幂等）
    const r = expectCode0(await rl(async () => nfy<{ read: boolean }>(
      await post(`${BASE}/nfy/api/v1/runtime/announcements/${id}/read`, undefined, RT(tA, u)))))
    expect(r.data.read, '第 2 次 markRead 仍成功（幂等）').toBe(true)
    const list = await runtimeList(tA, u)
    expect(list.data.list.find((i) => i.title === title)?.my_status, 'my_status→READ').toBe('READ')
    expect(await unconfirmed(tA, u), '阅读不动未确认数（确认才联动）').toBe(before)
    await evidence(page, 'L3-02-标记阅读幂等', {
      步骤: '第 1 次 POST /runtime/announcements/{id}/read 经 UI「标为已读」控件；第 2 次直调 API',
      两次响应: '均成功（UI 后 my_status=READ；API 再读 data.read=true）',
      my_status: 'READ', unconfirmed_count: `${before} → ${await unconfirmed(tA, u)}（不变）`,
      UI复核: '「标为已读」点击后控件消失（见 L3-02-UI标为已读操作后.png）',
    })
  })

  test('L3-03 ANN-003 confirm 幂等 + unconfirmed_count 恰好减 1', async () => {
    const title = `确认联动公告-${uniq('')}`
    const id = await createAnnouncement(plat, platBase, { title, needConfirm: 1, publish: true })
    const u = uniq('l3cu')
    const before = await unconfirmed(tA, u)
    expect(before, '确认前在本公告未确认集').toBeGreaterThanOrEqual(1)

    // 第 1 次 confirm 经真实 SPA 控件「我知道了」（UI 面·关键证据）
    await openUi(page, 'announcements', tA.token, u)
    const f = fl(page)
    const row = f.locator('li', { hasText: title })
    await expect(row.getByText('我知道了')).toBeVisible()
    await row.getByText('我知道了').click()
    await expect(row.getByText('已确认'), '确认后呈现「已确认」状态标签').toBeVisible()
    await shot(page, 'L3-03-UI我知道了确认后')

    // 第 2 次 confirm 走 API → 幂等（uk 兜底）
    const r = expectCode0(await rl(async () => nfy<{ confirmed: boolean }>(
      await post(`${BASE}/nfy/api/v1/runtime/announcements/${id}/confirm`, undefined, RT(tA, u)))))
    expect(r.data.confirmed, '第 2 次 confirm 仍 confirmed=true（幂等，uk 兜底）').toBe(true)
    expect(await unconfirmed(tA, u), 'unconfirmed_count 恰好 -1（重复确认不重复扣减）').toBe(before - 1)
    const list = await runtimeList(tA, u)
    expect(list.data.list.find((i) => i.title === title)?.my_status, 'my_status→CONFIRMED').toBe('CONFIRMED')
    await evidence(page, 'L3-03-确认幂等与未确认联动', {
      步骤: '第 1 次 POST /runtime/announcements/{id}/confirm 经 UI「我知道了」控件；第 2 次直调 API',
      unconfirmed_count: `${before} → ${before - 1}（恰好减 1）`,
      my_status: 'CONFIRMED',
      UI复核: '「我知道了」点击后呈现「已确认」标签（见 L3-03-UI我知道了确认后.png）',
    })
  })

  test('L3-04 AAN 租户公告管理流：DRAFT→发布→runtime 可见；biz_no 幂等 10401；下线即不可见', async () => {
    const bizNo = uniq('l3aan-')
    const title = `租户管理公告-${uniq('')}`
    const id = await createAnnouncement(tA.token, tenBase, { title, bizNo, publish: false })
    // biz_no 幂等闸（uk_tenant+biz_no）→ 10401
    const dup = await nfy(await post(tenBase, {
      title: '重复业务号', content: 'c', effective_at: Date.now() - 3600_000,
      expire_at: Date.now() + 20 * 60_000, biz_no: bizNo,
    }, { token: tA.token }))
    expect(dup.code, '同租户同 biz_no 重复创建 10401').toBe(10401)
    // 发布 → runtime 可见
    expectCode0(await nfy(await post(`${tenBase}/${id}/publish`, undefined, { token: tA.token })))
    expect((await runtimeList(tA, uniq('l3u'))).data.list.some((i) => i.title === title),
      'A 租户用户 runtime 可见').toBe(true)
    expect((await runtimeList(tB, uniq('l3u'))).data.list.some((i) => i.title === title),
      'B 租户不可见 A 公告').toBe(false)
    // 下线 → 立即不可见；重复下线 10402
    expectCode0(await nfy(await post(`${tenBase}/${id}/offline`, undefined, { token: tA.token })))
    expect((await runtimeList(tA, uniq('l3u'))).data.list.some((i) => i.title === title),
      '下线后 A 亦不可见').toBe(false)
    const again = await nfy(await post(`${tenBase}/${id}/offline`, undefined, { token: tA.token }))
    expect(again.code, '重复下线 10402').toBe(10402)
    await evidence(page, 'L3-04-租户公告管理流', {
      创建: { announcement_id: id, status: 'DRAFT → PUBLISHED → OFFLINE' },
      biz_no幂等: { code: dup.code, message: dup.message },
      可见性: { A发布后: true, B: false, A下线后: false },
      重复下线: { code: again.code },
      证据口径: 'API 面（admin 生命周期状态机/幂等码语义无 UI 表达；公告页消费的是 runtime 生效列表）',
    })
  })

  test('L3-05 PAN 平台公告确认回执跨域口径（第 28 步修复回归）：平台侧 detail confirm_count≥1', async () => {
    const title = `PAN确认域回归-${uniq('')}`
    const id = await createAnnouncement(plat, platBase, { title, needConfirm: 1, publish: true })
    const u = uniq('l3pu')
    // ⓪ 用户侧基线：未确认集含本公告
    const before = await unconfirmed(tA, u)
    expect(before, '基线 unconfirmed≥1').toBeGreaterThanOrEqual(1)
    // 租户用户阅读+确认
    expectCode0(await rl(async () => nfy(await post(`${BASE}/nfy/api/v1/runtime/announcements/${id}/read`, undefined, RT(tA, u)))))
    expectCode0(await rl(async () => nfy(await post(`${BASE}/nfy/api/v1/runtime/announcements/${id}/confirm`, undefined, RT(tA, u)))))
    // ① 用户侧：CONFIRMED + unconfirmed 恰减 1（回执落 0 域仍被 IN(0,租户) 反查扣减）
    expect((await runtimeList(tA, u)).data.list.find((i) => i.title === title)?.my_status).toBe('CONFIRMED')
    expect(await unconfirmed(tA, u), 'unconfirmed_count 恰减 1').toBe(before - 1)
    // ② 平台侧 detail（PAN-002）：confirm_count≥1（修前恒 0 的跨域双口径缺陷）
    const detail = expectCode0(await nfy<Record<string, any>>(await get(`${platBase}/${id}`, { token: plat })))
    expect(Number(detail.data.confirm_count), 'PAN detail confirm_count≥1（重点回归）').toBeGreaterThanOrEqual(1)
    expect(detail.data.need_confirm).toBe(1)
    // ③ 契约边界：PAN 契约仅 PAN-001~004（无 stats 端点，AAN-005 统计仅租户域）→ 404 信封 10400
    const stats = await nfy(await get(`${platBase}/${id}/stats`, { token: plat }))
    expect(stats.code, 'PAN 无 stats 端点（契约口径，非缺陷）').toBe(10400)
    // ④ 归属域防探测：租户 admin 面访问平台公告 → 10400
    const tenantAdmin = await nfy(await get(`${tenBase}/${id}`, { token: tA.token }))
    expect(tenantAdmin.code, '租户 admin 面访问平台公告 10400').toBe(10400)
    await evidence(page, 'L3-05-PAN确认跨域口径回归', {
      流程: '平台建 need_confirm=1 公告并发布 → 租户用户 read+confirm → 平台侧核账',
      用户侧: { my_status: 'CONFIRMED', unconfirmed_count: `${before} → ${before - 1}` },
      平台侧detail: { confirm_count: detail.data.confirm_count, need_confirm: detail.data.need_confirm },
      平台侧stats边界: { code: stats.code, message: stats.message, 口径: 'PAN 契约=PAN-001~004 无 stats 端点；confirm 核账经 PAN-002 detail.confirm_count 透出（非缺陷）' },
      租户admin面: { code: tenantAdmin.code, message: tenantAdmin.message },
      口径: '第 28 步修复回归：回执按公告归属域(tenant 0)落库，PAN detail confirm_count 可见',
      证据口径: 'API 面（平台域 detail/stats 与租户 admin 面防探测均无 UI 面）',
    })
  })

  test('L3-06 PTE-005 mandatory 唯一入口：仅平台域可设，租户域接口不暴露', async () => {
    const typeCode = uniq('L3M')
    expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: typeCode, name: 'L3强制类型', default_channels: ['INAPP'] }, { token: tA.token })))
    const mandatoryOf = async (): Promise<number | undefined> => {
      const r = expectCode0(await nfy<Array<Record<string, any>>>(
        await get(`${BASE}/nfy/api/v1/admin/types`, { token: tA.token })))
      return r.data.find((x) => x.type_code === typeCode)?.mandatory
    }
    expect(await mandatoryOf(), '初始 mandatory=0').toBe(0)
    // 唯一入口：平台域设置 mandatory=1
    const url = `${BASE}/nfy/platform/api/v1/tenants/${tA.open_id}/type-mandatory`
    expectCode0(await nfy(await post(url, { type_code: typeCode, mandatory: 1 }, { token: plat })))
    expect(await mandatoryOf(), '平台设置后租户侧可见 mandatory=1').toBe(1)
    // 参数校验：mandatory=2 → 10100；未知 open_id → 10400
    const bad = await nfy(await post(url, { type_code: typeCode, mandatory: 2 }, { token: plat }))
    expect(bad.code, 'mandatory 仅允许 0/1 → 10100').toBe(10100)
    const ghost = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/NOPE${uniq('')}/type-mandatory`,
      { type_code: typeCode, mandatory: 1 }, { token: plat }))
    expect(ghost.code, '未知租户 10400 防探测').toBe(10400)
    // 复位 0
    expectCode0(await nfy(await post(url, { type_code: typeCode, mandatory: 0 }, { token: plat })))
    expect(await mandatoryOf(), '复位后 mandatory=0').toBe(0)
    await evidence(page, 'L3-06-mandatory唯一入口', {
      契约: 'PTE-005：mandatory 唯一设置入口=平台域；租户域 admin/types 不暴露该字段（提交即忽略）',
      步骤: '建类型(mandatory=0) → 平台设 1 → 租户侧读回 1 → 非法值 10100 / 未知租户 10400 → 复位 0',
      校验: { mandatory2: bad.code, 未知租户: ghost.code },
      证据口径: 'API 面（平台域治理开关无租户 UI 面）',
    })
  })
})
