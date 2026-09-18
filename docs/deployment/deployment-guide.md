# 安装部署手册

> notification4j · 适用版本 v1.2.2 · 本文以代码与出厂配置为事实源（`backend/notification4j-app/src/main/resources/application.yml`、`backend/bin/run.sh`、`frontend/e2e-regression/bin/start-app.sh`）

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17+（实跑 21 验证） |
| 数据库 | PostgreSQL 16（Flyway 首启自动迁移 12 张表） |
| 缓存 | Redis 7（token 校验/防爆破/幂等/nonce/未读缓存） |
| 构建 | Maven（离线 `-o` 模式依赖 framework4j v1.7.1 已入本机 m2） |
| 前端（可选再生成） | Node 20+ / pnpm |

## 2. 构建产物

```bash
cd backend
mvn -o install -DskipTests          # starter 安装到本地 m2（其他模块依赖它）
mvn -o clean package -pl notification4j-app -DskipTests   # 独立部署 fat jar（必须 clean，见 backend/README 说明）
# 产物：notification4j-app/target/notification4j-app-1.3.0.jar（前端 SPA 已入 jar，免双部署）
```

## 3. 形态一：独立部署（fat jar，一个进程服务页面 + API）

```bash
JWT_SECRET=<32B+ 随机串> \
AES_KEY=<32B 随机串> \
PLATFORM_CLIENT_SECRET=<平台域密钥> \
java -jar notification4j-app/target/notification4j-app-1.3.0.jar
```

- **机密全部环境变量注入、缺失即启动失败（fail-fast），不入库不入 git**：
  | 环境变量 | 用途 | 缺省 |
  |---|---|---|
  | `JWT_SECRET` | access-token 签名密钥（≥32B） | 无（fail-fast） |
  | `AES_KEY` | 敏感列 AES 加密密钥（32B） | 无（fail-fast） |
  | `PLATFORM_CLIENT_SECRET` | 平台域 client 换 token 密钥 | 无（fail-fast） |
  | `HASH_SALT` | token hash 盐 | `salt` |
- 数据库/Redis 指向：出厂 `localhost:5432/notification4j`（admin/test@2026 为开发默认）+ `localhost:6379`，生产经 `spring.flyway.*` / `framework4j.datasource.datasources.default.*` / `framework4j.redis.datasources.default.*` 覆盖。
- 端口 9200；控制台 `http://localhost:9200/`；OpenAPI `/swagger-ui/index.html`；健康检查 `/nfy/api/v1/ops/health`。
- **安全面出厂默认**（v1.2.2）：runtime 不强制签名（`path-patterns=[]`，S2S 需要时自行加回）、druid `filters: stat,slf4j`（wall 不兼容 PG SKIP LOCKED）、原生 DataSource/Druid/MybatisPlus/TxManager 装配与 Redisson 由 starter 的 `NfyDataPlaneTakeoverFilter` 接管（单池口径，`backend/README.md`「数据面接管」）。详见 `backend/README.md` 安全面节与 `docs/api/api-spec.md` V1.2.2 修订。
- 生产建议：反向代理 TLS + 进程托管（systemd/K8s）；`backend/bin/run.sh` 含 G1GC 参考参数。

## 4. 形态二：嵌入业务方应用（starter，进程内直调）

引 `fun.commons.notification4j:notification4j-starter:1.3.0`（坐标版本以本地 m2 为准），
最小配置与 `@MapperScan` 约定见 `backend/README.md`「嵌入接入形态」。发消息走 `NotifyClient` 门面。

## 5. 形态三：跨进程接入（client-starter，零数据面）

业务方只发消息/公告时引 `notification4j-client-starter`，配置 `nfy.runtime.mode=remote` +
`remote-url/remote-tenant-id/remote-tenant-secret`，与嵌入形态同 FQCN 零改码切换。
完整契约见 `backend/README.md`「跨进程接入形态」与 `docs/operations/integration-guide.md`。

## 6. E2E 回归环境（验收/回归用）

```bash
docker start nfy4j-e2e-pg nfy4j-e2e-redis          # PG16(25432)/Redis7(26379) 独立容器
bash frontend/e2e-regression/bin/start-app.sh       # 出厂等价实例 :9200（detached；日志在 .runs/）
cd frontend && NFY_EVIDENCE_DIR=../docs/test/report/local-run/screenshots \
  npx playwright test -c e2e-regression/playwright.config.ts
```

## 7. 健康验证（部署后必做）

1. `curl http://<host>:9200/nfy/api/v1/ops/health` → `{"code":0,...,"status":"UP"}`（db/redis 双 UP）；
2. 浏览器打开控制台首页，确认消息中心页面可加载；
3. 平台域换 token（`POST /nfy/api/v1/auth/token`，client_id=PLATFORM）成功签发 JWT。
