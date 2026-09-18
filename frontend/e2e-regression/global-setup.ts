import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 全局前置：
 * 1. 校验被测实例（9200）已启动（未启动则提示 bin/start-app.sh）；
 * 2. 确保宿主握手页静态服务器（3000，origin 白名单内）在跑——不在则以 detached 方式拉起。
 */
const here = dirname(fileURLToPath(import.meta.url))
const hostDir = resolve(here, 'hosts')
const appLog = resolve(here, '.runs/e2e-app.log')

async function up(url: string, timeoutMs = 5000): Promise<boolean> {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const r = await fetch(url, { signal: ctrl.signal })
    return r.ok
  } catch {
    return false
  } finally {
    clearTimeout(t)
  }
}

export default async function globalSetup() {
  if (!(await up('http://localhost:9200/nfy/api/v1/ops/health'))) {
    throw new Error(`被测实例未启动（期望 http://localhost:9200）。
先执行: bash frontend/e2e-regression/bin/start-app.sh
实例日志: ${existsSync(appLog) ? appLog : '(不存在)'}`)
  }

  if (!(await up('http://localhost:3000/nfy-host.html'))) {
    // detached（start_new_session）：静态服务器随宿主 shell 退出不终止
    const p = spawn('python3', ['-m', 'http.server', '3000', '--bind', '127.0.0.1'], {
      cwd: hostDir,
      stdio: 'ignore',
      detached: true,
    })
    p.unref()
    for (let i = 0; i < 20; i++) {
      if (await up('http://localhost:3000/nfy-host.html', 1500)) return
      await new Promise((r) => setTimeout(r, 300))
    }
    throw new Error('宿主握手页静态服务器(3000)启动失败')
  }
}
