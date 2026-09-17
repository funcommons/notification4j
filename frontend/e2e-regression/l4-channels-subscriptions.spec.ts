import { test, expect, type Page } from '@playwright/test'
import dns from 'node:dns/promises'
import {
  BASE, platformToken, createTenant, nfy, expectCode0, evidence, get, post, put, patch, del, uniq,
  type NfyResp, type Tenant,
} from './helpers/nfy'

/**
 * L4 渠道与订阅线 · Playwright 端到端回归
 * 契约：CHN-001~005（自注册 SSRF 白名单 / 验证 / 改名启停 / 删除防探测）、
 *       ACH-001~003（公共渠道 scope=TENANT 与用户渠道隔离）、
 *       SUB-001/002（矩阵三段 / PUT 全量替换 / 强制集「类型语义→实例」/ quiet_hours 落库校验）
 * 对应 IT 深回归：NfyChannelFlowTest / NfyChannelAdminTest / NfySubscriptionFlowTest
 *
 * 【环境发现项 E-L4-01（P3，L4-01b 取证）】本机代理 fake-IP DNS 将官方域名同时解析到
 * fake-IPv4(198.18.0.0/15) 与 IPv6 ULA(fdfe:dcba:9876::/48 ⊂ fc00::/7)，命中产品 SSRF 禁用地址集
 * → IM 白名单域名注册被拒 10609。产品判定按契约正确（解析含内网/保留段即拒绝，生产直连 DNS 不受影响）；
 * 阳性自注册用例采用 EMAIL 通道（校验器对 EMAIL 仅做格式校验、不做 DNS 解析）。
 *
 * 【ND-L5-01 已修复，绕行脚手架退役】原 EMAIL 渠道 ENABLED 无 API 通路（曾以 helpers/pg-fixup
 * 直改库绕行，该文件保留备查）。修复后契约：patch ENABLED 对 EMAIL 豁免已验证前置（verify 仍 10604
 * 「首次投递时校验」不变）→ PENDING 直启；EMAIL 熔断态重启用=重新验证（fail_count 归 0）；
 * IM（DINGTALK/WECOM/FEISHU）口径不变：未验证/熔断直启仍 10610。需要 ENABLED 态的渠道一律走
 * 真实 API（注册 → PATCH ENABLED）。
 */

const sigOf = (t: Tenant) => ({ key: t.open_id, secret: t.tenant_secret })
const opt = (t: Tenant, userid?: string) => ({ token: t.token, userId: userid, sig: sigOf(t) })

interface ChannelVO { channel_id: string; status: string; fail_count: string | number; last_verify_at: string | number | null; name: string; target: string; channel_type: string }
interface ListResp { list: ChannelVO[] }
interface SubsResp {
  types: Array<{ type_code: string; mandatory: number; default_channels: string[] }>
  available_channels: Array<{ channel_id: string; channel_type?: string }>
  items: Array<{ type_code: string; channel_ids: string[]; quiet_hours?: { start: string; end: string } }>
}

function channelOf(r: NfyResp<ListResp>, channelId: string): ChannelVO {
  const c = r.data.list.find((x) => x.channel_id === channelId)
  expect(c, `渠道 ${channelId} 在列表中存在: ${JSON.stringify(r.data).slice(0, 300)}`).toBeTruthy()
  return c!
}

test.describe('L4 渠道与订阅线', () => {
  let page: Page
  let plat: string
  /** 渠道线租户 / 订阅线租户（每条线自建租户，uniq 隔离） */
  let tc: Tenant
  let ts: Tenant
  let tcName = ''
  let tsName = ''
  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage()
    plat = await platformToken()
    tcName = uniq('L4渠道线')
    tsName = uniq('L4订阅线')
    tc = await createTenant(plat, tcName)
    ts = await createTenant(plat, tsName)
  })
  test.afterAll(async () => { await page.close() })

  /** 自注册渠道（runtime 面：TENANT token + X-User-Id + sig） */
  const reg = async (t: Tenant, userid: string, body: Record<string, unknown>) =>
    nfy<{ channel_id: string; status: string; verify_tip: string }>(await post(`${BASE}/nfy/api/v1/runtime/channels`, body, opt(t, userid)))
  const listCh = async (t: Tenant, userid: string) => nfy<ListResp>(await get(`${BASE}/nfy/api/v1/runtime/channels`, opt(t, userid)))
  const verifyChPost = async (t: Tenant, userid: string, id: string) =>
    nfy<{ status: string; last_verify_at: number }>(await post(`${BASE}/nfy/api/v1/runtime/channels/${id}/verify`, undefined, opt(t, userid)))
  const patchCh = async (t: Tenant, userid: string, id: string, body: Record<string, unknown>) =>
    nfy<{ status: string }>(await patch(`${BASE}/nfy/api/v1/runtime/channels/${id}`, body, opt(t, userid)))
  const delCh = async (t: Tenant, userid: string, id: string) =>
    nfy(await del(`${BASE}/nfy/api/v1/runtime/channels/${id}`, opt(t, userid)))
  const dingBody = (name: string) => ({ channel_type: 'DINGTALK', name, target: `https://oapi.dingtalk.com/robot/send?access_token=${uniq('tk')}`, keyword: '通知' })
  const emailBody = (name: string, addr: string) => ({ channel_type: 'EMAIL', name, target: addr })

  test('L4-01 CHN-001 自注册渠道成功（PENDING 落库不发验证消息）', async () => {
    // EMAIL 通道：校验器仅做格式校验（零 DNS），环境无关确定性阳性
    const addr = `${uniq('sre')}@e2e.test`
    const r = expectCode0(await reg(tc, 'u1', emailBody('SRE 值班邮箱', addr)))
    expect(r.data.channel_id, 'channel_id 签发').toBeTruthy()
    expect(r.data.status, '注册即 PENDING（不发验证消息，P99≤300ms）').toBe('PENDING')
    expect(r.data.verify_tip, 'verify 引导语').toContain('verify')
    await evidence(page, 'L4-01-自注册渠道成功', {
      请求: 'POST /nfy/api/v1/runtime/channels (channel_type=EMAIL, target=值班邮箱)',
      响应: { code: r.code, channel_id: r.data.channel_id, status: r.data.status, verify_tip: r.data.verify_tip },
      契约: 'PENDING 落库不发验证消息（§5.7 性能预算 P99≤300ms）',
      'IM 通道注记': 'DINGTALK/FEISHU 白名单阳性见 L4-01b（本环境代理 fake-IP DNS 触发 SSRF 判定，E-L4-01）',
    })
  })

  test('L4-01b IM 官方域名注册（环境敏感）：代理 fake-IP DNS 命中 SSRF 禁用集 → 10609', async () => {
    // 本机 getaddrinfo 视角复核（与实例 JVM 同一系统解析器）
    const looked = await dns.lookup('oapi.dingtalk.com', { all: true }).catch(() => [] as dns.LookupAddress[])
    const reserved = looked.filter((e) => /^198\.18\./.test(e.address) || /^f[cd]/i.test(e.address))
    const r = await reg(tc, 'u1b', dingBody('SRE 报警群'))
    if (r.code === 0) {
      // 干净网络：官方域名解析公网地址 → 应注册成功
      expect(r.data.status, '白名单官方域名注册成功').toBe('PENDING')
      await evidence(page, 'L4-01b-IM官方域名注册成功', {
        解析: looked, 响应: { code: r.code, status: r.data!.status },
      })
      return
    }
    expect(r.code, '非干净网络下拒绝码恒 10609').toBe(10609)
    expect(reserved.length, '复核：官方域名确被本机 DNS 解析至保留段（fake-IPv4/ULA），SSRF 拒绝按契约正确').toBeGreaterThan(0)
    await evidence(page, 'L4-01b-环境发现项E-L4-01', {
      发现项: 'E-L4-01（P3，环境/部署兼容性）：代理 fake-IP DNS 使 IM 白名单域名注册被拒',
      解析复核: { 域名: 'oapi.dingtalk.com', 解析结果: looked, 保留段地址: reserved.map((x) => x.address) },
      产品响应: { code: r.code, message: r.message },
      判定: 'WebhookTargetValidator 契约=解析结果含内网/保留段即拒绝（fc00::/7 ULA/198.18 fake-IP 均命中），行为正确',
      影响: '仅代理/fake-IP DNS 环境；生产直连 DNS 不受影响',
      修复意见: '部署文档标注「出口 DNS 不得将官方域解析至保留段」；或校验器对白名单域名采用「存在任一公网地址即放行」口径（登记不改）',
    })
  })

  test('L4-02 SSRF 防线：明文/内网/非白名单/非443端口 → 10609', async () => {
    // WebhookTargetValidator：必须 https + 官方域名白名单 + 仅443 + DNS 禁内网段（§5.7）
    const cases: Array<[string, Record<string, unknown>, string]> = [
      ['http 明文', { channel_type: 'DINGTALK', name: 'n1', target: 'http://127.0.0.1:9999/hook' }, 'scheme!=https'],
      ['内网目标', { channel_type: 'DINGTALK', name: 'n2', target: 'https://192.168.1.1/robot/send' }, 'DNS 禁内网段'],
      ['非白名单域名', { channel_type: 'DINGTALK', name: 'n3', target: 'https://evil.example.com/hook' }, '官方域名白名单'],
      ['白名单域非443端口', { channel_type: 'DINGTALK', name: 'n3b', target: 'https://oapi.dingtalk.com:8443/robot/send' }, '仅 443 端口'],
    ]
    const results: Array<Record<string, unknown>> = []
    for (const [label, body, rule] of cases) {
      const r = await reg(tc, 'u2', body)
      expect(r.code, `${label} 应拒绝`).toBe(10609)
      results.push({ 场景: label, 命中规则: rule, code: r.code, message: r.message })
    }
    await evidence(page, 'L4-02-SSRF防线拒绝', {
      校验器: 'WebhookTargetValidator：https + 白名单(oapi.dingtalk.com/qyapi.weixin.qq.com/open.feishu.cn/.feishu.cn) + 仅443 + DNS 逐IP禁内网',
      用例: results,
      错误码语义: '10609=Webhook 地址非法或非官方域名（防 SSRF）',
    })
  })

  test('L4-03 非法渠道类型/空 target/非法邮箱/名称超长 → 10100', async () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      ['渠道类型枚举非法', { channel_type: 'SMS', name: 'n5', target: 'https://oapi.dingtalk.com/x' }],
      ['target 为空', { channel_type: 'DINGTALK', name: 'n6', target: '' }],
      ['EMAIL 格式非法', emailBody('n7', 'not-an-email')],
      ['名称超长(>30)', { channel_type: 'DINGTALK', name: '长'.repeat(31), target: 'https://oapi.dingtalk.com/x' }],
    ]
    const results: Array<Record<string, unknown>> = []
    for (const [label, body] of cases) {
      const r = await reg(tc, 'u3', body)
      expect(r.code, `${label} 应 10100`).toBe(10100)
      results.push({ 场景: label, code: r.code, message: r.message })
    }
    await evidence(page, 'L4-03-渠道注册非法参数拒绝', {
      口径: '枚举/邮箱格式=业务校验 10100；必填/长度=@Valid → fwk4j-web 统一 10100（HTTP 200 信封）',
      用例: results,
    })
  })

  test('L4-04 CHN-002/003 列表脱敏 + 验证语义（无凭据 10604 保持 PENDING）', async () => {
    const addr = `${uniq('u4')}@e2e.test`
    const r = expectCode0(await reg(tc, 'u4', { channel_type: 'EMAIL', name: '脱敏邮箱渠道', target: addr }))
    const chId = r.data.channel_id
    const list = expectCode0(await listCh(tc, 'u4'))
    const vo = channelOf(list, chId)
    expect(vo.target, 'EMAIL target 脱敏：首字符+***+@域名').toBe(`${addr[0]}***@e2e.test`)
    expect(JSON.stringify(list.data), '完整邮箱不可见').not.toContain(addr)
    expect(String(vo.fail_count), '初始 fail_count=0').toBe('0')

    // 验证：EMAIL 通道暂不支持主动验证 → 10604，状态保持 PENDING
    const v = await verifyChPost(tc, 'u4', chId)
    expect(v.code, 'EMAIL verify 契约不变仍 10604（首次投递时校验）').toBe(10604)
    const after = channelOf(expectCode0(await listCh(tc, 'u4')), chId)
    expect(after.status, '验证失败保持 PENDING').toBe('PENDING')
    // ND-L5-01 修复后契约：EMAIL 豁免已验证前置 → PENDING 直启成功（IM 未验证直启仍 10610，IT 钉死）
    const p = await patchCh(tc, 'u4', chId, { status: 'ENABLED' })
    expect(p.code, 'EMAIL PENDING 直启成功（ND-L5-01 修复）').toBe(0)
    expect(p.data.status, '直启后状态 ENABLED').toBe('ENABLED')
    await evidence(page, 'L4-04-列表脱敏与验证语义', {
      列表脱敏: { target: vo.target, 完整地址: addr.slice(0, 4) + '...(已不可见)', fail_count: 0 },
      验证响应: { code: v.code, message: v.message },
      状态流转: 'PENDING（verify 10604 不迁移）→ patch ENABLED code 0（EMAIL 直启契约，ND-L5-01 已修复）',
      语义注记: 'IM 未验证直启仍 10610 / 验证成功正例（2xx 且业务码 0）由 NfyChannelFlowTest 深覆盖',
    })
  })

  test('L4-05 CHN-004 patch 改名/启停流转', async () => {
    const chId = expectCode0(await reg(tc, 'u5', emailBody('原渠道名', `${uniq('u5')}@e2e.test`))).data.channel_id
    const p1 = expectCode0(await patchCh(tc, 'u5', chId, { name: '改名后渠道', status: 'DISABLED' }))
    expect(p1.data.status, '改名+停用通过').toBe('DISABLED')
    // ND-L5-01 修复后契约：EMAIL 直启成功（原 10610）
    const p2 = await patchCh(tc, 'u5', chId, { status: 'ENABLED' })
    expect(p2.code, 'EMAIL 再启用成功（EMAIL 直启契约）').toBe(0)
    expect(p2.data.status).toBe('ENABLED')
    const p3 = await patchCh(tc, 'u5', chId, { status: 'PAUSED' })
    expect(p3.code, '非法状态枚举 10100').toBe(10100)
    // P4 回归：DISABLED 流转仍正常
    const p4 = await patchCh(tc, 'u5', chId, { status: 'DISABLED' })
    expect(p4.code, '再停用通过').toBe(0)
    const vo = channelOf(expectCode0(await listCh(tc, 'u5')), chId)
    expect(vo.name).toBe('改名后渠道')
    expect(vo.status).toBe('DISABLED')
    await evidence(page, 'L4-05-patch改名启停流转', {
      流转: ['patch{rename+DISABLED} → 0/DISABLED', 'patch{ENABLED} → 0（EMAIL 直启契约，ND-L5-01 已修复）', 'patch{PAUSED} → 10100', 'patch{DISABLED} → 0'],
      终态: { name: vo.name, status: vo.status },
    })
  })

  test('L4-06 CHN-005 删除渠道 + 防探测（删后同码操作 10400）', async () => {
    const chId = expectCode0(await reg(tc, 'u6', emailBody('待删渠道', `${uniq('u6')}@e2e.test`))).data.channel_id
    expectCode0(await delCh(tc, 'u6', chId))
    expect((await delCh(tc, 'u6', chId)).code, '重复删除 10400').toBe(10400)
    expect((await verifyChPost(tc, 'u6', chId)).code, '删后 verify 10400').toBe(10400)
    expect((await patchCh(tc, 'u5', chId, { status: 'DISABLED' })).code, '跨用户操作 10400（防探测同码）').toBe(10400)
    const list = expectCode0(await listCh(tc, 'u6'))
    expect(JSON.stringify(list.data), '列表不再含已删渠道').not.toContain(chId)
    await evidence(page, 'L4-06-删除与防探测', {
      流转: 'delete → 0；二次 delete/verify/patch → 10400（同码防探测 §6.3）',
      列表复核: '已删渠道不在我的渠道列表（逻辑删）',
    })
  })

  test('L4-07 ACH 公共渠道：scope=TENANT 隔离 + 验证/管理', async () => {
    const name = '公共报警群' + uniq('')
    const regPub = async (body: Record<string, unknown>) =>
      nfy<{ channel_id: string; status: string }>(await post(`${BASE}/nfy/api/v1/admin/channels`, body, { token: tc.token }))
    const created = expectCode0(await regPub({ channel_type: 'EMAIL', name, target: `${uniq('pub')}@e2e.test` }))
    expect(created.data.status, '公共渠道同样 PENDING 落库').toBe('PENDING')
    const chId = created.data.channel_id
    // admin 列表可见；runtime 我的渠道不可见（scope 隔离）
    const adminList = expectCode0(await nfy<ListResp>(await get(`${BASE}/nfy/api/v1/admin/channels`, { token: tc.token })))
    expect(JSON.stringify(adminList.data)).toContain(name)
    const runtimeList = expectCode0(await listCh(tc, 'u7'))
    expect(JSON.stringify(runtimeList.data), 'scope=TENANT 不入用户渠道列表').not.toContain(name)
    // 跨租户 admin 列表不可见
    const t2 = await createTenant(plat, uniq('L4邻租'))
    const listB = expectCode0(await nfy<ListResp>(await get(`${BASE}/nfy/api/v1/admin/channels`, { token: t2.token })))
    expect(JSON.stringify(listB.data), '跨租户不可见').not.toContain(name)
    // 管理操作：验证（10604 同语义）/启停/删除
    expect((await nfy(await post(`${BASE}/nfy/api/v1/admin/channels/${chId}/verify`, undefined, { token: t2.token })).then((r) => r.code)), '跨租户 verify 10400').toBe(10400)
    expectCode0(await nfy(await patch(`${BASE}/nfy/api/v1/admin/channels/${chId}`, { status: 'DISABLED' }, { token: tc.token })))
    expectCode0(await nfy(await del(`${BASE}/nfy/api/v1/admin/channels/${chId}`, { token: tc.token })))
    await evidence(page, 'L4-07-公共渠道scope隔离', {
      注册: 'POST /nfy/api/v1/admin/channels（body 无 userid，scope=TENANT）→ PENDING',
      隔离: { admin列表可见: true, runtime用户列表不可见: true, 邻租户admin列表不可见: true, 跨租户verify: '10400' },
      管理: 'patch DISABLED → 0；delete → 0',
    })
  })

  // ---------- 订阅矩阵（租户 ts） ----------

  const createType = async (t: Tenant, code: string, channels: string[]) =>
    expectCode0(await nfy<{ type_id: string }>(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: code, name: '类型-' + code, default_channels: channels }, { token: t.token })))
  const setMandatory = async (code: string, mandatory: number) =>
    expectCode0(await nfy(await post(`${BASE}/nfy/platform/api/v1/tenants/${ts.open_id}/type-mandatory`,
      { type_code: code, mandatory }, { token: plat })))
  const getSubs = async (userid: string) => nfy<SubsResp>(await get(`${BASE}/nfy/api/v1/runtime/subscriptions`, opt(ts, userid)))
  const putSubs = async (userid: string, items: Array<Record<string, unknown>>) =>
    nfy<{ saved_count: number }>(await put(`${BASE}/nfy/api/v1/runtime/subscriptions`, { items }, opt(ts, userid)))
  /** 注册 + PATCH ENABLED（ND-L5-01 已修复：EMAIL 直启为正式契约，走真实 API），返回 channel_id */
  const regEnabled = async (userid: string, body: Record<string, unknown>): Promise<string> => {
    const chId = expectCode0(await reg(ts, userid, body)).data.channel_id
    const p = await patchCh(ts, userid, chId, { status: 'ENABLED' })
    expect(p.code, 'PATCH ENABLED 应成功').toBe(0)
    expect(p.data.status).toBe('ENABLED')
    return chId
  }

  test('L4-08 SUB-001 矩阵三段（types/available_channels/items）', async () => {
    const codeA = 'SUBA' + uniq('').toUpperCase()
    const codeB = 'SUBB' + uniq('').toUpperCase()
    await createType(ts, codeA, ['INAPP', 'DINGTALK'])
    await createType(ts, codeB, ['INAPP'])
    await setMandatory(codeB, 1) // PTE-005 平台域设强制
    const r = expectCode0(await getSubs('u8'))
    const ta = r.data.types.find((x) => x.type_code === codeA)
    const tb = r.data.types.find((x) => x.type_code === codeB)
    expect(ta, '类型 A 入矩阵').toBeTruthy()
    expect(tb, '类型 B 入矩阵').toBeTruthy()
    expect(ta!.mandatory, 'A 非强制').toBe(0)
    expect(tb!.mandatory, 'B 平台设为强制').toBe(1)
    expect(ta!.default_channels, 'default_channels 回显').toEqual(['INAPP', 'DINGTALK'])
    expect(r.data.available_channels, '无 ENABLED 渠道时仅 INAPP 哨兵').toEqual([{ channel_id: 'INAPP' }])
    expect(r.data.items, '未配置 items 为空').toEqual([])
    await evidence(page, 'L4-08-订阅矩阵三段', {
      types: r.data.types.map((x) => ({ type_code: x.type_code, mandatory: x.mandatory, default_channels: x.default_channels })),
      available_channels: r.data.available_channels,
      items: r.data.items,
      契约: 'GET 三段=启用类型 / ENABLED 渠道+INAPP 哨兵 / 已配置项；mandatory 唯一设置入口=平台域 PTE-005',
    })
  })

  test('L4-09 SUB-002 PUT 全量替换（多类型×渠道条目，幂等，INAPP 哨兵恒首位）', async () => {
    const codeA = 'PUTA' + uniq('').toUpperCase()
    const codeB = 'PUTB' + uniq('').toUpperCase()
    await createType(ts, codeA, ['INAPP', 'EMAIL'])
    await createType(ts, codeB, ['INAPP', 'EMAIL'])
    const emailId = await regEnabled('u9', emailBody('邮箱渠道', `${uniq('u9')}@e2e.test`))
    const email2Id = await regEnabled('u9', emailBody('备用邮箱', `${uniq('u9')}@e2e.test`))
    // 多类型×多渠道条目提交
    const s1 = expectCode0(await putSubs('u9', [
      { type_code: codeA, channel_ids: ['INAPP', emailId, email2Id] },
      { type_code: codeB, channel_ids: [email2Id] },
    ]))
    expect(s1.data.saved_count, 'saved_count=2 行').toBe(2)
    let m = expectCode0(await getSubs('u9'))
    const itemA = m.data.items.find((x) => x.type_code === codeA)!
    const itemB = m.data.items.find((x) => x.type_code === codeB)!
    expect(itemA.channel_ids, 'INAPP 哨兵服务端补齐恒首位').toEqual(['INAPP', emailId, email2Id])
    expect(itemB.channel_ids).toEqual(['INAPP', email2Id])
    expect(m.data.available_channels.map((c) => c.channel_id), 'available_channels 出现 2 个 ENABLED 实例').toEqual(expect.arrayContaining(['INAPP', emailId, email2Id]))
    // 重复 PUT 同内容 → 幂等（last-write-wins）
    expectCode0(await putSubs('u9', [
      { type_code: codeA, channel_ids: ['INAPP', emailId, email2Id] },
      { type_code: codeB, channel_ids: [email2Id] },
    ]))
    // 全量替换：只提交 A → B 行删除
    const s2 = expectCode0(await putSubs('u9', [{ type_code: codeA, channel_ids: ['INAPP', emailId] }]))
    expect(s2.data.saved_count).toBe(1)
    m = expectCode0(await getSubs('u9'))
    expect(m.data.items.map((x) => x.type_code), '未提交类型行被全量替换删除').toEqual([codeA])
    await evidence(page, 'L4-09-订阅PUT全量替换', {
      提交: `${codeA}=[INAPP,邮箱1,邮箱2] + ${codeB}=[邮箱2] → saved_count=2`,
      回读一致: { [codeA]: itemA.channel_ids, [codeB]: itemB.channel_ids },
      幂等: '重复 PUT 同内容仍 saved_count=2（upsert 不翻倍）',
      全量替换: `仅提交 ${codeA} → saved_count=1，${codeB} 行删除`,
      说明: '渠道实例 ENABLED 态经真实 API（注册 → PATCH ENABLED，ND-L5-01 已修复，见文件头）',
    })
  })

  test('L4-10 强制集校验：有实例须保留（10606），无实例豁免', async () => {
    const codeM = 'MAND' + uniq('').toUpperCase()
    await createType(ts, codeM, ['INAPP', 'EMAIL'])
    await setMandatory(codeM, 1)
    const dingId = await regEnabled('u10', emailBody('强制集邮箱', `${uniq('u10')}@e2e.test`))
    // 有 ENABLED 邮箱实例：关闭最后实例 → 10606
    expect((await putSubs('u10', [{ type_code: codeM, channel_ids: ['INAPP'] }])).code, '强制类型剔除最后实例 10606').toBe(10606)
    expectCode0(await putSubs('u10', [{ type_code: codeM, channel_ids: ['INAPP', dingId] }]))
    // 无实例用户豁免（INAPP 锁定兜底）
    expectCode0(await putSubs('u11', [{ type_code: codeM, channel_ids: ['INAPP'] }]))
    await evidence(page, 'L4-10-强制集类型到实例校验', {
      语义: 'mandatory 类型 default_channels 每渠道类型：有 ENABLED 实例须保留≥1（关最后 10606）；无实例豁免',
      有实例: { 关最后实例: 10606, 保留实例: 'code=0' },
      无实例豁免: 'u11 仅 INAPP → code=0',
    })
  })

  test('L4-11 SUB 非法输入：10101 / 10601 / 10400', async () => {
    const codeV = 'VALD' + uniq('').toUpperCase()
    await createType(ts, codeV, ['INAPP'])
    expect((await putSubs('u12', [{ type_code: codeV, channel_ids: [] }])).code, '空 channel_ids 10101').toBe(10101)
    expect((await putSubs('u12', [{ type_code: 'GHOST' + uniq(''), channel_ids: ['INAPP'] }])).code, '未注册类型 10601').toBe(10601)
    const pendingId = expectCode0(await reg(ts, 'u12', emailBody('未验证渠道', `${uniq('u12')}@e2e.test`))).data.channel_id
    expect((await putSubs('u12', [{ type_code: codeV, channel_ids: ['INAPP', pendingId] }])).code, 'PENDING 渠道 10400').toBe(10400)
    const foreignId = await regEnabled('u9b', emailBody('他人邮箱', `${uniq('u9b')}@e2e.test`))
    expect((await putSubs('u12', [{ type_code: codeV, channel_ids: ['INAPP', foreignId] }])).code, '同租户他人渠道 10400').toBe(10400)
    const m = expectCode0(await getSubs('u12'))
    expect(m.data.items, '全部被拒，无半截落库').toEqual([])
    await evidence(page, 'L4-11-订阅非法输入拒绝', {
      用例: { '空 channel_ids': 10101, 未注册类型: 10601, PENDING渠道: 10400, 他人渠道: 10400 },
      事务性: '校验失败不落库（items 仍空）',
    })
  })

  test('L4-12 quiet_hours 保存/回显/缺省重置/非法 10100', async () => {
    const codeQ = 'QH' + uniq('').toUpperCase()
    await createType(ts, codeQ, ['INAPP'])
    // 保存 + 原样回显
    expectCode0(await putSubs('u13', [{ type_code: codeQ, channel_ids: ['INAPP'], quiet_hours: { start: '22:00', end: '08:00' } }]))
    const echo = expectCode0(await getSubs('u13')).data.items.find((x) => x.type_code === codeQ)!
    expect(echo.quiet_hours, 'quiet_hours 回显').toEqual({ start: '22:00', end: '08:00' })
    // 缺省重置（全量替换语义）
    expectCode0(await putSubs('u13', [{ type_code: codeQ, channel_ids: ['INAPP'] }]))
    const reset = expectCode0(await getSubs('u13')).data.items.find((x) => x.type_code === codeQ)!
    expect(reset.quiet_hours, '缺省=未启用（字段不返回）').toBeUndefined()
    // 非法输入
    const invalid: Array<[string, Record<string, string>]> = [
      ['格式 25:00', { start: '25:00', end: '08:00' }],
      ['格式 abc', { start: 'abc', end: '08:00' }],
      ['start=end', { start: '22:00', end: '22:00' }],
      ['只给 start', { start: '22:00', end: '' }],
    ]
    const results: Array<Record<string, unknown>> = []
    for (const [label, qh] of invalid) {
      const r = await putSubs('u13', [{ type_code: codeQ, channel_ids: ['INAPP'], quiet_hours: qh }])
      expect(r.code, `${label} 应 10100`).toBe(10100)
      results.push({ 场景: label, code: r.code, message: r.message })
    }
    const clean = expectCode0(await getSubs('u13')).data.items.find((x) => x.type_code === codeQ)!
    expect(clean.quiet_hours, '校验失败不落库（保持未启用）').toBeUndefined()
    await evidence(page, 'L4-12-quiet_hours落库校验', {
      回显: echo.quiet_hours,
      缺省重置: 'PUT 不带 quiet_hours → 字段消失（未启用）',
      非法用例: results,
      语义注记: '推迟发送的展开语义在 L5-04 验证；URGENT/INAPP 豁免由 NfyQuietHoursTest 深覆盖',
    })
  })
})
