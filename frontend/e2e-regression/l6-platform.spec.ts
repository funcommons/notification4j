import { test, expect, type Page } from '@playwright/test'
import {
  BASE, mintToken, platformToken, createTenant, nfy, expectCode0,
  evidence, get, post, patch, uniq, type Tenant,
} from './helpers/nfy'

/**
 * L6 平台治理与租户生命周期线 · Playwright 端到端回归
 * 契约：PTE-001~005（建租户 secret 仅一次/email 唯一、配置修改与脱敏、reset-secret 宽限、
 *       状态机 ACTIVE⇄SUSPENDED→CLOSED 终态、平台域 mandatory 唯一入口）、
 *       PST-001（平台跨租户概览）、平台域闸（租户 token 打平台面 403）
 * 对应 IT 深回归：NfyPlatformTenantTest / NfyPlatformDomainTest / NfySignatureKeyTest(宽限列)
 * 实测口径（live 9200 复核）：SUSPEND → mint 401 同码防探测；存量 token → 10201（会话撤销）；
 * reset-secret 旧密钥 24h 宽限内仍可换 token（§5.5 双版本过渡，NfyPlatformTenantTest 钉死）。
 */
test.describe('L6 平台治理与租户生命周期线', () => {
  let page: Page
  test.beforeAll(async ({ browser }) => { page = await browser.newPage() })
  test.afterAll(async () => { await page.close() })

  let plat = ''
  let tenA = { openId: '', secret: '', email: '' }
  let tenB: Tenant | null = null
  let tokenB = ''
  let tenC: Tenant | null = null
  let tenD: Tenant | null = null
  let tokenD = ''

  test('L6-01 PTE-001 建租户：secret 仅此一次返回 + 响应不含内部数字 id + 可认证', async () => {
    plat = await platformToken()
    const name = uniq('治理租户甲')
    tenA.email = `${uniq('e')}-${Date.now()}@e2e.test`
    const r = expectCode0(await nfy<Record<string, string>>(await post(`${BASE}/nfy/platform/api/v1/tenants`,
      { name, email: tenA.email, privileges: { signature: false }, config: { retentionDays: 30 } }, { token: plat })))
    expect(Object.keys(r.data).sort(), '响应仅 open_id/tenant_secret').toEqual(['open_id', 'tenant_secret'])
    expect(r.data.open_id, '外部 open_id').toBeTruthy()
    expect(r.data.tenant_secret, '明文 secret 仅此一次返回').toBeTruthy()
    expect(JSON.stringify(r.data), '不得出现内部数字 id').not.toMatch(/"id"/)
    const auth = expectCode0(await mintToken(r.data.open_id, r.data.tenant_secret))
    expect(auth.data.access_token, '新租户立即具备认证能力').toBeTruthy()
    tenA.openId = r.data.open_id
    tenA.secret = r.data.tenant_secret
    await evidence(page, 'L6-01-建租户secret仅一次', {
      请求: 'POST /nfy/platform/api/v1/tenants',
      响应字段: Object.keys(r.data),
      open_id: tenA.openId,
      tenant_secret: tenA.secret.slice(0, 8) + '...(截断)',
      内部数字id: JSON.stringify(r.data).match(/"id"/) ? '【失败】出现' : '未出现（open_id 混淆出网）',
      新租户换token: 'code=0 成功',
    })
  })

  test('L6-02 PTE-001 email 唯一约束：同邮箱重复创建 10401 拒绝', async () => {
    const r = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants`,
      { name: uniq('重复邮箱租户'), email: tenA.email }, { token: plat }))
    expect(r.code, '唯一闸拒绝').toBe(10401)
    await evidence(page, 'L6-02-email唯一约束', {
      请求: 'POST /tenants（与 L6-01 同邮箱）',
      响应: { code: r.code, message: r.message },
      结论: 'nfya_tenant.email 唯一索引真闸，明文可读文案「邮箱已被使用」',
    })
  })

  test('L6-03 PTE-002 配置修改 + 详情 email 脱敏 + 列表可见', async () => {
    expectCode0(await nfy(await patch(`${BASE}/nfy/platform/api/v1/tenants/${tenA.openId}`,
      { oem: { title: '甲的白标标题' } }, { token: plat })))
    const d = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/platform/api/v1/tenants/${tenA.openId}`, { token: plat })))
    const local = tenA.email.split('@')[0]
    const masked = local[0] + '***' + (local.length > 1 ? local.slice(-1) : '') + '@' + tenA.email.split('@')[1]
    expect(d.data.email, 'email 脱敏为 首1***尾1@域名').toBe(masked)
    expect(JSON.stringify(d.data), '明文 email 不得回显').not.toContain(tenA.email)
    expect(JSON.stringify(d.data.oem), 'PATCH oem 回显').toContain('甲的白标标题')
    expect(d.data.privileges, '三组配置回显 privileges').toEqual({ signature: false })
    const list = expectCode0(await nfy<{ list: Array<Record<string, unknown>> }>(await get(`${BASE}/nfy/platform/api/v1/tenants`, { token: plat })))
    expect(list.data.list.map((t) => t.open_id)).toContain(tenA.openId)
    expect(JSON.stringify(list.data), '列表亦脱敏').not.toContain(tenA.email)
    await evidence(page, 'L6-03-配置修改与脱敏', {
      patch: 'oem.title=甲的白标标题 → code=0',
      详情email: d.data.email + '（期望 ' + masked + '）',
      oem回显: d.data.oem,
      列表: '含 ' + tenA.openId + '，全程无明文 email',
    })
  })

  test('L6-04 PTE-004 SUSPEND：新认证 401 同码防探测 + 存量 token 双面 10201 阻断', async () => {
    tenB = await createTenant(plat, uniq('停用租户乙'))
    const st = expectCode0(await nfy<Record<string, string>>(await post(
      `${BASE}/nfy/platform/api/v1/tenants/${tenB.open_id}/status`, { action: 'SUSPEND' }, { token: plat })))
    expect(st.data.status).toBe('SUSPENDED')
    const mint = await mintToken(tenB.open_id, tenB.tenant_secret)
    expect(mint.code, 'SUSPEND 后正确凭据换 token 亦 401（同码防探测）').toBe(401)
    const onAdmin = await nfy(await get(`${BASE}/nfy/api/v1/admin/types`, { token: tenB.token }))
    expect(onAdmin.code, '存量 token 打 admin 面被阻（会话撤销）').toBe(10201)
    const onRuntime = await nfy(await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`,
      { token: tenB.token, userId: 'u_suspend' }))
    expect(onRuntime.code, '存量 token 打 runtime 面被阻').toBe(10201)
    await evidence(page, 'L6-04-SUSPEND阻断', {
      状态迁移: 'ACTIVE → SUSPENDED（code=0）',
      新认证: { code: mint.code, message: mint.message, 口径: '401 同码防探测（不泄露停用态）' },
      存量token_admin面: { code: onAdmin.code, message: onAdmin.message },
      存量token_runtime面: { code: onRuntime.code, message: onRuntime.message },
      结论: 'SUSPEND 即撤销全部存量会话（TenantSessionRevoker），双面立即失效',
    })
  })

  test('L6-05 PTE-004 RESUME → ACTIVE：认证恢复', async () => {
    const st = expectCode0(await nfy<Record<string, string>>(await post(
      `${BASE}/nfy/platform/api/v1/tenants/${tenB!.open_id}/status`, { action: 'RESUME' }, { token: plat })))
    expect(st.data.status).toBe('ACTIVE')
    const mint = expectCode0(await mintToken(tenB!.open_id, tenB!.tenant_secret))
    tokenB = mint.data.access_token
    const use = expectCode0(await nfy(await get(`${BASE}/nfy/api/v1/admin/types`, { token: tokenB })))
    expect(use.code, '恢复后 token 可正常调用业务面').toBe(0)
    await evidence(page, 'L6-05-RESUME恢复', {
      状态迁移: 'SUSPENDED → ACTIVE（code=0）',
      换token: 'code=0（原 secret 直接恢复）',
      业务调用: 'admin/types code=0',
    })
  })

  test('L6-06 PTE-003 reset-secret：新 secret 立即生效 + 旧 secret 24h 宽限（§5.5 双版本过渡）', async () => {
    const r = expectCode0(await nfy<Record<string, string>>(await post(
      `${BASE}/nfy/platform/api/v1/tenants/${tenA.openId}/reset-secret`, {}, { token: plat })))
    expect(Object.keys(r.data).sort()).toEqual(['open_id', 'tenant_secret'])
    const fresh = r.data.tenant_secret
    expect(fresh, '新明文不等于旧明文').not.toBe(tenA.secret)
    const mintNew = expectCode0(await mintToken(tenA.openId, fresh))
    const mintOld = await mintToken(tenA.openId, tenA.secret)
    expect(mintOld.code, '旧 secret 在 24h 宽限期内仍可换 token（§5.5，NfyPlatformTenantTest 钉死口径）').toBe(0)
    await evidence(page, 'L6-06-reset-secret宽限', {
      reset: 'POST /tenants/{id}/reset-secret → 新明文仅此一次（len=' + fresh.length + '）',
      新secret认证: { code: mintNew.code, 结论: '立即生效' },
      旧secret认证: { code: mintOld.code, 结论: '宽限期内（prev+prev_at 起算 24h）仍可换 token' },
      契约注记: '验收任务书写「旧 secret 立即失效」；产品契约 §5.5 为双版本宽限过渡，IT 深回归同口径 —— 按契约判定通过，口径差异已在报告登记',
    })
  })

  test('L6-07 PTE-004 CLOSE 终态：再迁移 10402 + 认证不可恢复 + 非法 action 10100', async () => {
    tenC = await createTenant(plat, uniq('注销租户丙'))
    expectCode0(await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/${tenC.open_id}/status`,
      { action: 'CLOSE' }, { token: plat })))
    const reSuspend = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/${tenC.open_id}/status`,
      { action: 'SUSPEND' }, { token: plat }))
    expect(reSuspend.code, 'CLOSED 为终态，再 SUSPEND 拒绝').toBe(10402)
    const mint = await mintToken(tenC.open_id, tenC.tenant_secret)
    expect(mint.code, 'CLOSE 后认证不可恢复').toBe(401)
    const illegal = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/${tenA.openId}/status`,
      { action: 'DELETE' }, { token: plat }))
    expect(illegal.code, '非法 action 10100').toBe(10100)
    await evidence(page, 'L6-07-CLOSE终态', {
      CLOSE: 'code=0 → status=CLOSED',
      再SUSPEND: { code: reSuspend.code, message: reSuspend.message },
      换token: { code: mint.code, message: mint.message },
      非法action: { action: 'DELETE', code: illegal.code, message: illegal.message },
      结论: 'ACTIVE⇄SUSPENDED→CLOSED 单向状态机，终态不可逆',
    })
  })

  test('L6-08 PST-001 平台跨租户概览：造数据后字段齐全与计数单调合理', async () => {
    const before = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/platform/api/v1/stats/overview`, { token: plat })))
    tenD = await createTenant(plat, uniq('概览租户丁'))
    tokenD = tenD.token
    const tc = uniq('PSTTY')
    expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: tc, name: '概览类型', default_channels: ['INAPP'] }, { token: tokenD })))
    for (let i = 1; i <= 2; i++) {
      expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/runtime/messages`,
        { type_code: tc, user_ids: ['u' + i], title: '概览消息' + i, content: 'c', biz_no: uniq('pstbiz') }, { token: tokenD })))
    }
    const after = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/platform/api/v1/stats/overview`, { token: plat })))
    expect(['tenant_count', 'active_tenants', 'today_messages', 'today_deliveries',
      'deliver_success_rate', 'dead_count', 'dead_tenants'].every((k) => k in after.data), '字段齐全').toBe(true)
    expect(Number(after.data.tenant_count), '建租户后租户数单调 +≥1')
      .toBeGreaterThanOrEqual(Number(before.data.tenant_count) + 1)
    expect(Number(after.data.today_messages), '发 2 条后今日消息数单调 +≥2')
      .toBeGreaterThanOrEqual(Number(before.data.today_messages) + 2)
    expect(Number(after.data.active_tenants), 'active 租户数合理').toBeGreaterThanOrEqual(1)
    const rate = Number(after.data.deliver_success_rate)
    expect(rate, '成功率万分比定标 0~10000').toBeGreaterThanOrEqual(0)
    expect(rate).toBeLessThanOrEqual(10000)
    expect(Number(after.data.dead_count), '全时段 DEAD 计数').toBeGreaterThanOrEqual(0)
    expect(Array.isArray(after.data.dead_tenants), 'dead_tenants 为 open_id 维度数组').toBe(true)
    await evidence(page, 'L6-08-平台跨租户概览', {
      口径: '时间基 created_at 自然日；deliver_success_rate=SUCCESS/(SUCCESS+FAILED+DEAD) 万分比；空数据日取 0',
      造数: '自建租户丁 + INAPP 类型 + 2 条消息',
      before: before.data,
      after: after.data,
    })
  })

  test('L6-09 平台域闸：租户 token 打平台面端点 → 403 拒绝', async () => {
    const r1 = await nfy(await get(`${BASE}/nfy/platform/api/v1/tenants`, { token: tokenD }))
    expect(r1.code, '租户 token 打租户列表 403').toBe(403)
    expect(r1.message).toContain('平台域')
    const r2 = await nfy(await get(`${BASE}/nfy/platform/api/v1/stats/overview`, { token: tokenD }))
    expect(r2.code, '租户 token 打平台统计 403').toBe(403)
    await evidence(page, 'L6-09-平台域越权拒绝', {
      请求1: 'GET /nfy/platform/api/v1/tenants（租户 token）',
      响应1: { code: r1.code, message: r1.message },
      请求2: 'GET /nfy/platform/api/v1/stats/overview（租户 token）',
      响应2: { code: r2.code, message: r2.message },
      结论: '@PlatformDomain 域闸校验 tenant_id==0，跨域越权不可达',
    })
  })

  test('L6-10 PTE-005 强制订阅唯一入口：平台域 type-mandatory 设置 + 参数/租户校验', async () => {
    const tc = uniq('MAND')
    expectCode0(await nfy(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: tc, name: '强制订阅类型', default_channels: ['INAPP'] }, { token: tokenB })))
    const set = expectCode0(await nfy<Record<string, unknown>>(await post(
      `${BASE}/nfy/platform/api/v1/tenants/${tenB!.open_id}/type-mandatory`,
      { type_code: tc, mandatory: 1 }, { token: plat })))
    expect(set.data.mandatory).toBe(1)
    const list = expectCode0(await nfy<{ list?: Array<Record<string, unknown>> } | Array<Record<string, unknown>>>(
      await get(`${BASE}/nfy/api/v1/admin/types`, { token: tokenB })))
    const items = Array.isArray(list.data) ? list.data : (list.data.list ?? [])
    const target = items.find((t) => t.type_code === tc)
    expect(target && target.mandatory, '落库 mandatory=1（admin 列表回显）').toBe(1)
    const ill = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/${tenB!.open_id}/type-mandatory`,
      { type_code: tc, mandatory: 2 }, { token: plat }))
    expect(ill.code, 'mandatory 仅允许 0/1').toBe(10100)
    const ghost = await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/NOPE123/type-mandatory`,
      { type_code: tc, mandatory: 1 }, { token: plat }))
    expect(ghost.code, '未知租户 10400').toBe(10400)
    await evidence(page, 'L6-10-强制订阅唯一入口', {
      设置: 'POST /platform/tenants/{open_id}/type-mandatory {type_code, mandatory:1} → code=0',
      admin列表回显: { type_code: tc, mandatory: target && target.mandatory },
      非法枚举: { mandatory: 2, code: ill.code },
      未知租户: { code: ghost.code, message: ghost.message },
      结论: 'mandatory 唯一设置入口在平台域（admin 面不暴露该字段）',
    })
  })
})
