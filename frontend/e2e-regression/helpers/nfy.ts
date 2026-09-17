import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * notification4j e2e 回归 helper：
 * - HTTP 全部走 node 原生 fetch（Node26 下 Playwright APIRequestContext 存在 context 过早 dispose 问题）；
 * - HMAC 签名复刻 AuthenticatedHttpTransport/NfyHmacSigner 口径：STS=METHOD\nPATH(去query)\nTS\nNONCE\nBODY_MD5；
 * - 平台/租户引导（建租户、建类型等）；
 * - 证据：响应渲染成 HTML 页并截图到 documents/test-report/screenshots/。
 */

export const BASE = process.env.NFY_BASE ?? 'http://localhost:9200'
export const PLATFORM_SECRET = 'platform-secret-e2e'
// 约定：playwright 始终从 frontend/ 目录以 `npx playwright test -c e2e-regression/...` 启动（cwd=frontend）
const SHOTS = path.resolve(process.cwd(), '../documents/test-report/screenshots')

// ---------- 签名 ----------

export function md5Hex(s: string): string {
  return crypto.createHash('md5').update(s, 'utf8').digest('hex')
}

export function signSts(secret: string, method: string, pathNoQuery: string, ts: string, nonce: string, bodyMd5: string): string {
  return crypto.createHmac('sha256', secret).update([method, pathNoQuery, ts, nonce, bodyMd5].join('\n'), 'utf8').digest('hex')
}

function splitUrl(url: string): { pathNoQuery: string; query: string } {
  const i = url.indexOf('?')
  return i >= 0 ? { pathNoQuery: url.slice(0, i), query: url.slice(i) } : { pathNoQuery: url, query: '' }
}

// ---------- 响应与证据 ----------

export interface NfyResp<T = unknown> {
  status: number
  code: number
  message: string
  data: T
  trace_id: string
}

export async function nfy<T = unknown>(r: Response): Promise<NfyResp<T>> {
  const text = await r.text()
  let j: NfyResp<T>
  try {
    j = JSON.parse(text) as NfyResp<T>
  } catch {
    j = { status: r.status, code: -1, message: '非JSON响应: ' + text.slice(0, 200), data: null as T, trace_id: '' }
  }
  j.status = r.status
  return j
}

/** 证据截图：name 形如 "L2-03-bizno幂等" */
export async function evidence(page: Page, name: string, payload: unknown): Promise<string> {
  fs.mkdirSync(SHOTS, { recursive: true })
  const file = path.join(SHOTS, `${name}.png`)
  const json = JSON.stringify(payload, null, 2)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
  await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>
    body{font:13px/1.5 Menlo,monospace;margin:0;background:#0f172a;color:#e2e8f0}
    h1{font-size:14px;margin:0;padding:10px 14px;background:#1e293b;border-bottom:1px solid #334155}
    pre{margin:0;padding:12px 14px;white-space:pre-wrap;word-break:break-all}
    .ok{color:#4ade80}.tag{color:#94a3b8}
  </style></head><body>
  <h1><span class="tag">证据 ·</span> ${name} <span class="tag">· ${new Date().toISOString()}</span></h1>
  <pre>${json}</pre></body></html>`)
  await page.screenshot({ path: file, fullPage: true })
  return file
}

/** 断言 code=0 信封，失败时把响应也留在错误信息里 */
export function expectCode0<T>(r: NfyResp<T>): NfyResp<T> {
  expect(r.code, `code=0 期望被违反: ${JSON.stringify(r).slice(0, 400)}`).toBe(0)
  return r
}

// ---------- HTTP 调用（原生 fetch） ----------

interface CallOpts {
  token?: string
  userId?: string
  /** runtime 面签名凭据 */
  sig?: { key: string; secret: string }
  form?: Record<string, string>
}

async function call(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', url: string, opts: CallOpts, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = {}
  let payload: string | undefined
  if (opts.form) {
    payload = new URLSearchParams(opts.form).toString()
    headers['Content-Type'] = 'application/x-www-form-urlencoded'
  } else if (body !== undefined) {
    payload = JSON.stringify(body)
    headers['Content-Type'] = 'application/json'
  }
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`
  if (opts.userId) headers['X-User-Id'] = opts.userId
  if (opts.sig) {
    const { pathNoQuery } = splitUrl(url.replace(BASE, ''))
    const ts = String(Date.now())
    const nonce = crypto.randomUUID()
    headers['X-Access-Key'] = opts.sig.key
    headers['X-Timestamp'] = ts
    headers['X-Nonce'] = nonce
    headers['X-Signature'] = signSts(opts.sig.secret, method, pathNoQuery, ts, nonce, md5Hex(payload ?? ''))
  }
  const abs = url.startsWith('http') ? url : `${BASE}${url}`
  return fetch(abs, { method, headers, body: payload })
}

export function get(url: string, opts: CallOpts = {}): Promise<Response> {
  return call('GET', url, opts)
}
export function post(url: string, body?: unknown, opts: CallOpts = {}): Promise<Response> {
  return call('POST', url, opts, body)
}
export function put(url: string, body?: unknown, opts: CallOpts = {}): Promise<Response> {
  return call('PUT', url, opts, body)
}
export function patch(url: string, body?: unknown, opts: CallOpts = {}): Promise<Response> {
  return call('PATCH', url, opts, body)
}
export function del(url: string, opts: CallOpts = {}): Promise<Response> {
  return call('DELETE', url, opts)
}

// ---------- 认证 ----------

export async function mintToken(clientId: string, clientSecret: string): Promise<NfyResp<{ access_token: string }>> {
  return nfy(await call('POST', `${BASE}/nfy/api/v1/auth/token`, {
    form: { grant_type: 'client_credentials', client_id: clientId, client_secret: clientSecret },
  }))
}

// ---------- 引导 ----------

export interface Tenant {
  open_id: string
  tenant_secret: string
  token: string
}

/** 平台 token */
export async function platformToken(): Promise<string> {
  return (await expectCode0(await mintToken('PLATFORM', PLATFORM_SECRET))).data.access_token
}

/** 平台面新建租户（PTE-001）并换租户 token */
export async function createTenant(plat: string, name: string): Promise<Tenant> {
  const r = expectCode0(await nfy<Record<string, string>>(await call('POST', `${BASE}/nfy/platform/api/v1/tenants`, {
    token: plat,
  }, {
    name,
    email: `${name.replace(/[^a-zA-Z0-9]/g, '')}-${Date.now()}@e2e.test`,
    privileges: { signature: false },
    config: { retentionDays: 30 },
  })))
  const open_id = r.data.open_id
  const tenant_secret = r.data.tenant_secret
  const token = (await expectCode0(await mintToken(open_id, tenant_secret))).data.access_token
  return { open_id, tenant_secret, token }
}

/** 轮询直到条件成立（引擎异步语义用） */
export async function until<T>(fn: () => Promise<T>, cond: (v: T) => boolean, timeoutMs = 15000, stepMs = 400): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (cond(v)) return v
    if (Date.now() > deadline) throw new Error(`until 超时(${timeoutMs}ms)，最后值: ${JSON.stringify(v)?.slice(0, 300)}`)
    await new Promise((r) => setTimeout(r, stepMs))
  }
}

/** 唯一后缀（防跨线数据串扰） */
export function uniq(prefix: string): string {
  return `${prefix}${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`
}
