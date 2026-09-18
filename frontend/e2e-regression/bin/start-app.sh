#!/bin/bash
# e2e 回归测试实例启动脚本（仅测试实例正当旋钮：端口/引擎提速/SMTP 目标；
# 出厂偏差覆盖已由 app application.yml 修复取代——签名面/druid filters/redisson 见 D-1/D-3/D-4）
# 前置：docker 容器 nfy4j-e2e-pg(25432) / nfy4j-e2e-redis(26379) 已启动
# detached：start_new_session 脱离进程组，避免命令超时连带 SIGTERM 杀掉 java
set -e
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
JAR="$ROOT/backend/notification4j-app/target/notification4j-app-1.0.0.jar"
LOG="$ROOT/frontend/e2e-regression/.runs/e2e-app.log"

export PLATFORM_CLIENT_SECRET=platform-secret-e2e
export JWT_SECRET=e2e-jwt-secret-e2e-jwt-secret-e2e-123456
export AES_KEY=12345678901234567890123456789012
export HASH_SALT=e2e-salt

DS="jdbc:postgresql://localhost:25432/notification4j?stringtype=unspecified"

ARGS=(
  --spring.datasource.url="$DS"
  --spring.flyway.url="$DS"
  --framework4j.datasource.datasources.default.url="$DS"
  --framework4j.redis.datasources.default.port=26379
  --nfy.runtime.engine.scan-interval-ms=500
  --nfy.runtime.engine.backoff-seconds=2,5,10
  --nfy.runtime.engine.reaper-interval-ms=1000
  --nfy.runtime.engine.sending-stale-ms=5000
  --nfy.runtime.engine.rate-limit-per-minute=600
  --nfy.runtime.engine.mail-host=localhost
  --nfy.runtime.engine.mail-port=3925
  --nfy.runtime.engine.mail-from=nfy-e2e@test.local
)

python3 - "$JAR" "$LOG" "${ARGS[@]}" <<'PYEOF'
import subprocess, sys
jar, log = sys.argv[1], sys.argv[2]
with open(log, 'w') as f:
    p = subprocess.Popen(['java', '-jar', jar, *sys.argv[3:]],
                         stdout=f, stderr=subprocess.STDOUT,
                         stdin=subprocess.DEVNULL, start_new_session=True)
print("APP_PID=" + str(p.pid))
open('/tmp/nfy4j-e2e-app.pid', 'w').write(str(p.pid))
PYEOF
