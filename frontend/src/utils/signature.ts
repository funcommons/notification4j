import CryptoJS from 'crypto-js'

/**
 * benefit4j runtime 域 HMAC-SHA256 签名 (与 framework4j SignatureUtil 对齐).
 *
 * 后端契约 (framework4j-signature):
 *   stringToSign = METHOD\nPATH\nTIMESTAMP\nNONCE\nBODY_MD5_HEX   (5 段 \n 分隔, 结尾无 \n)
 *   signature    = Base64( HmacSHA256(secret, stringToSign) )     (标准 base64, 保留 padding)
 *   bodyMd5      = MD5(原始 body 字节) 的 32 字符小写 hex (空 body = md5("") = d41d8cd9...)
 *
 * Header: X-Access-Key(tenant_id) / X-Timestamp(ms) / X-Nonce(uuid) / X-Signature
 * 时间窗 ±5min, nonce 10min 一次性 (Redis SETNX), 故每次请求 nonce 必须唯一.
 *
 * PATH 不含 query string (后端取 request.getRequestURI()).
 * body MD5 必须基于实际发出的 JSON 字节 — axios transformRequest 对 object 用 JSON.stringify,
 * 此处亦用 JSON.stringify, 同一对象序列化结果确定, 与发送字节一致.
 */

export interface SignatureHeaders {
  'X-Access-Key': string
  'X-Timestamp': string
  'X-Nonce': string
  'X-Signature': string
}

/** runtime 域路径需签名 (与后端 framework4j.signature.path-patterns 一致) */
export function isRuntimeUrl(url: string): boolean {
  // config.url 可能无前导 / (如 'benefit/api/v1/runtime/...'), 规范化后匹配
  const path = url.split('?')[0] ?? ''
  const norm = path.startsWith('/') ? path : '/' + path
  return norm.includes('/benefit/api/v1/runtime/')
}

export function buildSignatureHeaders(
  method: string,
  url: string,
  secret: string,
  accessKey: string,
  body?: unknown,
): SignatureHeaders {
  const timestamp = String(Date.now())
  const nonce = crypto.randomUUID()
  const path = normalizePath(url)
  const bodyStr = serializeBody(body)
  const bodyMd5 = CryptoJS.MD5(bodyStr).toString() // 32 hex lowercase
  const toSign = [method.toUpperCase(), path, timestamp, nonce, bodyMd5].join('\n')
  const signature = CryptoJS.HmacSHA256(toSign, secret).toString(CryptoJS.enc.Base64)
  return {
    'X-Access-Key': accessKey,
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': signature,
  }
}

/** 规范化 path: 去 query, 确保前导 / (后端 getRequestURI 含前导 /) */
function normalizePath(url: string): string {
  const path = url.split('?')[0] ?? ''
  return path.startsWith('/') ? path : '/' + path
}

function serializeBody(body?: unknown): string {
  if (body === undefined || body === null) return ''
  if (typeof body === 'string') return body
  return JSON.stringify(body)
}
