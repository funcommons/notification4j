/**
 * authBus: 跨标签页登录态广播
 *
 * 用途:
 *   - A 页被踢/过期 → B/C 页同步跳转登录页 (避免「A 跳了 B 还在操作」的用户困惑)
 *   - 跨标签页 token 续期 (后续接 refresh 后可用)
 *
 * 设计:
 *   - BroadcastChannel: 同源跨标签页/跨窗口实时通信; 不支持时降级 storage 事件
 *   - 单例懒加载: 第一次访问时建 channel, 之后复用
 *   - 事件自洽: 同标签页 emit 不会再被自己的 onMessage 接收到
 *
 * 注意:
 *   - 不传输 token (走 BroadcastChannel 仍可能被同源其他脚本读到); 只传「事件 + code」
 *   - 服务端 SSR 环境 BroadcastChannel 不存在, 整个模块走空实现
 */

export type AuthEvent =
  | { type: 'auth-expired'; code: number; timestamp: number }

type Listener = (ev: AuthEvent) => void

const CHANNEL_NAME = 'benefit4j-auth'

class AuthBus {
  private channel: BroadcastChannel | null = null
  private listeners: Set<Listener> = new Set()

  constructor() {
    if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') return
    try {
      this.channel = new BroadcastChannel(CHANNEL_NAME)
    } catch {
      // 极少数环境 BroadcastChannel 构造抛错 (Safari 私密模式), 静默降级
      this.channel = null
    }
  }

  emit(ev: AuthEvent): void {
    this.channel?.postMessage(ev)
    // 同时落 localStorage 触发同源 storage 事件 (BroadcastChannel 不可用时降级)
    try {
      // 同一秒内同事件只写一次 (避免重渲染抖动)
      const key = `benefit4j:auth-bus:${ev.type}`
      const last = Number(sessionStorage.getItem(key) || 0)
      if (Date.now() - last > 500) {
        sessionStorage.setItem(key, String(ev.timestamp))
        localStorage.setItem(key, JSON.stringify(ev))
      }
    } catch {
      // noop
    }
  }

  on(listener: Listener): () => void {
    this.listeners.add(listener)
    if (!this.channel) return () => this.listeners.delete(listener)
    const handler = (e: MessageEvent<AuthEvent>) => listener(e.data)
    this.channel.addEventListener('message', handler)
    return () => {
      this.channel?.removeEventListener('message', handler)
      this.listeners.delete(listener)
    }
  }
}

// 单例
export const authBus = new AuthBus()