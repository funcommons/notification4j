# 部署手册：独立部署与三形态选型

> notification4j 使用手册 · 05-部署与运维第 1 篇 · 版本口径 **v1.4.0**（fat jar `notification4j-app-1.4.0.jar`）。
> 本文迁移并升级自旧 `docs/deployment/deployment-guide.md`（原文件已下线）。
> 每个配置键、端口、环境变量与路径均核对自仓内源码：`backend/notification4j-app/src/main/resources/application.yml`（下称 app yml）、
> starter 的 `NfyProperties` / `DeliveryEngine`、`backend/README.md`、E2E 启动脚本 `frontend/e2e-regression/bin/start-app.sh`。
> 日常运维（引擎调优 / 投递干预 / 故障速查）见同目录 [operations.md](operations.md)；全量配置项见 [configuration.md](configuration.md)。

## 1. 环境要求

| 项 | 要求 | 出处 |
|---|---|---|
| JDK | 17+（底座 Spring Boot 3.2.7） | `backend/pom.xml` |
| 数据库 | PostgreSQL 16（首启 Flyway 自动迁移：V1.0.0 建 12 张表 + V1.0.1 `updated_at` 触发器） | starter `db/migration/` |
| 缓存 | Redis 7（access-token 校验、防爆破锁、幂等键、签名 nonce、限流滑窗、tracelog 聚合均落 default 数据源） | app yml `framework4j.*` 各段 |
| 构建 | Maven 3.6+（依赖 framework4j v1.7.1，经 JitPack 拉取；首次构建勿加 `-o`） | `backend/README.md` |
| 前端（可选再生成） | Node 20+ / pnpm（SPA 已预置入 jar，仅改前端时需要） | `backend/README.md`「前端产物再生成」 |

- **时区**：全部时间列为 `timestamptz`（DDL），应用层 `OffsetDateTime` 按 ISO-8601 带偏移序列化——部署无时区硬性要求；
  建议宿主机 / JVM / PG 统一时区，避免日志时间与库内时间错位对读。
- **资源口径（如实声明）**：仓内无官方压测容量基线，本文不给 CPU/内存数字建议。可用的出厂锚点：
  Druid 连接池 `max-active=50`、Lettuce 池 `max-active=50`、外发引擎 1 条调度线程 + 4 个投递 worker（均为 app yml / `NfyProperties` 出厂值）。

## 2. 产物与获取

**产物**：`notification4j-app/target/notification4j-app-1.4.0.jar` —— 可执行 fat jar（前端 SPA 已内嵌于
starter classpath `/nfy-console/`，一个进程同时服务页面与 API，免双部署）；repackage 前的 thin jar 存为 `.jar.original`。

```bash
# 自构建（backend/README.md「部署」节核实的命令；-o 离线模式前提是 framework4j v1.7.1 已入本机 m2）
cd backend
mvn install -DskipTests                                  # 首次构建：安装 starter 到本地 m2（首次勿加 -o）
mvn clean package -pl notification4j-app -DskipTests     # 打 fat jar（必须 clean：app 无改动时 jar 插件会沿用旧 fat jar）
```

- 改过前端后的再生成流程（`pnpm build` → 拷贝 dist → install starter → clean package）见 `backend/README.md`「前端产物再生成」。
- **业务方接入无需自构建 backend**：经 JitPack 按 tag 引入（坐标取自手册 README §4）——
  `com.github.funcommons.notification4j:notification4j-starter:v1.4.0`（嵌入）/ 同前缀 `notification4j-client-starter:v1.4.0`（跨进程）。

## 3. 形态一：独立部署（fat jar）

### 3.1 必填环境变量（缺失即启动失败，fail-fast）

机密全部经环境变量注入、不入库不入 git（app yml 占位符 `${...}` 无默认值即 fail-fast）：

| 环境变量 | 配置落点（app yml） | 约束 | 缺省 |
|---|---|---|---|
| `JWT_SECRET` | `framework4j.access-token.secret-key` | ≥32 字符（256 位）随机串——fwk4j `AccessTokenConfigValidator` 按**字符数**校验，不足即启动中止（access-token 签名密钥） | 无（fail-fast） |
| `AES_KEY` | `framework4j.sensitive.encryption-key` | ≥32 字符随机串（不足 32 字符启动即 `IllegalStateException`；实际密钥由内部 SHA-256 派生 32 字节） | 无（fail-fast） |
| `PLATFORM_CLIENT_SECRET` | `framework4j.tenant.platform.client-secret` | 平台域 client 换 token 密钥 | 无（fail-fast） |
| `PLATFORM_CLIENT_ID` | `framework4j.tenant.platform.client-id` | 平台域 client 标识 | `PLATFORM` |
| `HASH_SALT` | `framework4j.access-token.hash-salt` | token hash 盐 | `salt` |

```bash
# 生产请用随机值（示例命令，非密钥建议）：openssl rand -base64 32
JWT_SECRET=<32 字符+ 随机串> AES_KEY=<32 字符+ 随机串> PLATFORM_CLIENT_SECRET=<平台域密钥> \
java -jar notification4j-app/target/notification4j-app-1.4.0.jar
```

### 3.2 数据源参数（PostgreSQL 16）

出厂指向 `localhost:5432/notification4j`（用户 `admin` / 密码 `test@2026`，开发默认），生产按下表覆盖
（命令行 `--key=value`，E2E `start-app.sh` 同款方式）：

| 配置键 | 出厂默认 | 说明 |
|---|---|---|
| `spring.flyway.url` | `jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified` | Flyway 独立数据源（绕过 Druid Wall，见 3.4） |
| `spring.flyway.user` / `spring.flyway.password` | `admin` / `test@2026` | 同上 |
| `framework4j.datasource.datasources.default.url` | 同 flyway url | 业务主数据源（Druid 池） |
| `framework4j.datasource.datasources.default.username` / `.password` | `admin` / `test@2026` | 同上 |

- **JDBC url 必须保留 `?stringtype=unspecified`**（JSONB 列写入所需；出厂 url 自带，拼接新 url 时勿丢）。
- 覆盖库地址时**两处 url 都要改**（flyway 一处、业务池一处），漏改 flyway 会表现为「迁移走了旧库」。
- Druid 出厂池参数（app yml）：`filters: stat,slf4j`（**wall 显式关闭**——不识别 PG 方言的 `FOR UPDATE OF d SKIP LOCKED`
  与部分索引 WHERE，开启会拦死外发引擎与 Flyway；如需 wall 先验证这两条链路）、`initial-size/min-idle=10`、
  `max-active=50`、`max-wait=1000`、`test-while-idle + validation-query: SELECT 1`。
- **profile 注意**：app yml 出厂 `spring.profiles.active: dev`（写在**无条件生效的首段**），且全部 `framework4j.*` / `nfy.*`
  配置位于 `dev` profile 段下（`spring.config.activate.on-profile: dev`）。因此裸 `java -jar` **无需**显式
  `--spring.profiles.active=dev`——dev 已在 jar 内默认激活，即「出厂等价实例」；但若运维按环境切换 profile
  （如 `--spring.profiles.active=prod`），dev 段整体失效，须自行提供等价配置段——不补配时 framework4j 数据面闸
  （`datasource.enabled` / `redis.enabled`，缺省 false）不会开启，进程无业务 DataSource/Redis。

### 3.3 Redis 7

| 配置键 | 出厂默认 | 说明 |
|---|---|---|
| `framework4j.redis.datasources.default.host` | `localhost` | |
| `framework4j.redis.datasources.default.port` | `6379` | |

Lettuce 池出厂：`max-active=50 / max-idle=20 / min-idle=5`；`redisson.enabled=false`（本产品全链零 redisson，
显式关闭防误连宿主机默认 6379）。

### 3.4 Flyway 行为（升级必读）

- `enabled: true`，脚本位置 `classpath:db/migration`（在 starter resources，与实体同源）。
- **`baseline-on-migrate: false`**：只接受**空库**——全新库首启自动从 V1.0.0 建到最新；已含任意对象的库**不会静默
  baseline**，迁移直接失败，须先人工清理。升级注意：不要把服务指向带无关对象的「脏」库。
- **`validate-on-migrate: true`**：已应用脚本的 checksum 与库内记录不一致即启动中止（防篡改）。升级注意：**升级前切勿
  手工修改历史迁移脚本**，变更一律走新增 `V*` 脚本；跨版本升级步骤见 [../07-版本与支撑/upgrade-guide.md](../07-版本与支撑/upgrade-guide.md)。

### 3.5 启动命令

标准启动（**出厂默认参数**：端口 9200、本机 PG/Redis、dev profile）：

```bash
JWT_SECRET=<32 字符+ 随机串> \
AES_KEY=<32 字符+ 随机串> \
PLATFORM_CLIENT_SECRET=<平台域密钥> \
java -jar notification4j-app-1.4.0.jar
```

指向远端中间件 / 自定义端口的覆盖写法：

```bash
DS="jdbc:postgresql://pg-host:5432/notification4j?stringtype=unspecified"
java -jar notification4j-app-1.4.0.jar \
  --spring.flyway.url="$DS" --spring.flyway.user=nfy --spring.flyway.password='<pg 密码>' \
  --framework4j.datasource.datasources.default.url="$DS" \
  --framework4j.datasource.datasources.default.username=nfy \
  --framework4j.datasource.datasources.default.password='<pg 密码>' \
  --framework4j.redis.datasources.default.host=redis-host \
  --framework4j.redis.datasources.default.port=6379 \
  --server.port=9200
```

- 优雅停机：出厂 `server.shutdown=graceful` + 30s 上限——`kill <pid>`（SIGTERM）后处理完在途请求再退出。
- 生产建议：反向代理终止 TLS + 进程托管（systemd / K8s）；日志走 stdout 由托管器收集（见 [operations.md](operations.md) §5）。
- **提速参数仅测试用**（E2E `start-app.sh` 为缩短回归时长而覆盖，生产勿用）：
  `--nfy.runtime.engine.scan-interval-ms=500`、`--nfy.runtime.engine.backoff-seconds=2,5,10`、
  `--nfy.runtime.engine.reaper-interval-ms=1000`、`--nfy.runtime.engine.sending-stale-ms=5000`、
  `--nfy.runtime.engine.rate-limit-per-minute=600`、`--nfy.runtime.engine.mail-host=localhost --nfy.runtime.engine.mail-port=3925`
  （末两项指向 E2E 本地 SMTP 汇）。参数语义见 [operations.md](operations.md) §2。

### 3.6 启动验证

1. **健康端点**（免鉴权，app yml `access-token.exclude-path-patterns` 放行）：

   ```bash
   curl -s http://localhost:9200/nfy/api/v1/ops/health
   # 期望 data.status=UP 且 checks.db/redis 均 UP（探针：db=SELECT 1、redis=PING）
   ```

2. **单池日志口径**（数据面由 starter `NfyDataPlaneTakeoverFilter` 接管，原生装配退场）：启动日志中
   原生 Druid wrapper 计 **0**、HikariPool 计 **0**、ERROR 计 **0**（「单池三零」，出处：docs/test/report/2026-09-19-01、
   2026-09-21-01 统一测试报告）。
3. **引擎启动行**（用了外发渠道时必看）：
   `[Engine] 外发引擎已启动 scan=5000ms batch=50 reaper=60000ms`（`DeliveryEngine#start`）。
4. 页面入口：控制台 `http://localhost:9200/`、OpenAPI `/swagger-ui/index.html`、tracelog 控制台 `/tracelog/index.html`。

## 4. 形态二：嵌入业务方应用（starter，进程内直调）

引 `fun.commons.notification4j:notification4j-starter:1.4.0`（JitPack 坐标见 §2），最小配置与 `@MapperScan` 约定
完整 walkthrough 见 [../02-快速开始/quickstart-embed.md](../02-快速开始/quickstart-embed.md)：

```yaml
nfy:
  data:
    enabled: true          # 数据面闸（配合 @MapperScan("fun.commons.notification4j.mapper")）
  runtime:
    mode: local            # 进程内直调 service
    enable-api: false      # 不暴露三域 API（引擎/门面照常可用）
    engine:
      enabled: false       # 外发引擎默认关；宿主需要站外投递时显式 true
```

**数据面接管（v1.3.0 起）**：`framework4j.datasource.enabled` / `framework4j.redis.enabled` 缺省均为 false
（接管 filter `matchIfMissing` 语义），嵌入方**必须显式置 true** 才会装配 framework4j 多数据源/多 Redis。
置 true 后 starter 会 veto 原生 `DataSourceAutoConfiguration` / `DruidDataSourceAutoConfigure` /
`MybatisPlusAutoConfiguration` / `DataSourceTransactionManagerAutoConfiguration` / `RedissonAutoConfigurationV2/V4`——
接入方**无需** `spring.datasource.*`、**无需** `spring.autoconfigure.exclude`（旧文档的两项手工步骤已废除），
只配 `framework4j.datasource.datasources.default.*` 即可启动（IT `NfyDataPlaneTakeoverTest` 验证）。

## 5. 形态三：跨进程接入（client-starter，零数据面）

业务方只发消息/公告、调独立部署实例且不想引入 MyBatis/PG/Redis 时，引
`fun.commons.notification4j:notification4j-client-starter:1.4.0`，配 `nfy.runtime.mode=remote` +
`remote-url` / `remote-tenant-id` / `remote-tenant-secret`（HTTP + S2S JWT + HMAC 签名自动注入，
与嵌入形态同 FQCN `NotifyClient` 零改码切换）。完整步骤见 [../02-快速开始/quickstart-remote.md](../02-快速开始/quickstart-remote.md)。

## 6. docker compose 参考样例

> **示例**（仓内无官方 Dockerfile/镜像，本样例按 app yml 期望参数拼装；密钥均为占位，务必替换）。

```yaml
services:
  nfy-pg:
    image: postgres:16            # 与集成测试 Testcontainers 同大版本
    environment:
      POSTGRES_USER: nfy
      POSTGRES_PASSWORD: "<pg 密码>"      # 出厂开发默认是 admin / test@2026，生产请换
      POSTGRES_DB: notification4j         # Flyway 要求空库（baseline-on-migrate=false）
    volumes: [nfy-pgdata:/var/lib/postgresql/data]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U nfy -d notification4j"], interval: 5s, timeout: 3s, retries: 12 }

  nfy-redis:
    image: redis:7-alpine
    volumes: [nfy-redisdata:/data]

  nfy-app:
    image: eclipse-temurin:17-jre
    restart: unless-stopped
    depends_on: { nfy-pg: { condition: service_healthy }, nfy-redis: { condition: service_started } }
    volumes: [ "./notification4j-app-1.4.0.jar:/app/app.jar:ro" ]
    environment:
      JWT_SECRET: "<32 字符+ 随机串>"
      AES_KEY: "<32 字符+ 随机串>"
      PLATFORM_CLIENT_SECRET: "<平台域密钥>"
      TZ: Asia/Shanghai                # 可选：统一容器时区（应用时间列为 timestamptz，无硬性要求）
    command: >
      java -jar /app/app.jar
      --spring.flyway.url=jdbc:postgresql://nfy-pg:5432/notification4j?stringtype=unspecified
      --spring.flyway.user=nfy --spring.flyway.password=<pg 密码>
      --framework4j.datasource.datasources.default.url=jdbc:postgresql://nfy-pg:5432/notification4j?stringtype=unspecified
      --framework4j.datasource.datasources.default.username=nfy
      --framework4j.datasource.datasources.default.password=<pg 密码>
      --framework4j.redis.datasources.default.host=nfy-redis
    ports: [ "9200:9200" ]

volumes: { nfy-pgdata: {}, nfy-redisdata: {} }
```

要点：两处 DB url 都指向服务名并保留 `stringtype=unspecified`；机密走 environment（勿写入 compose 文件入 git，
可用 `.env`/secret 管理）；app 探活从宿主 `curl http://localhost:9200/nfy/api/v1/ops/health`（health 端点判读见
[operations.md](operations.md) §1）。

## 7. 部署后健康验证清单

| # | 动作 | 通过标准 |
|---|---|---|
| 1 | `curl http://<host>:9200/nfy/api/v1/ops/health` | `data.status=UP`（db/redis 双 UP） |
| 2 | 换平台 token：`POST /nfy/api/v1/auth/token`，表单 `grant_type=client_credentials&client_id=PLATFORM&client_secret=<密钥>` | 返回 JWT `access_token`（有效期出厂 8h=28800s；连续错 5 次锁 15min 返回 429） |
| 3 | 发一条 INAPP：租户 token 建类型 → `POST /nfy/api/v1/runtime/messages`（`default_channels:["INAPP"]` 落库即达，`inapp_saved=true`） | `code=0`，未读数立即可查（完整 curl 链见 [../02-快速开始/quickstart-5min.md](../02-快速开始/quickstart-5min.md)） |
| 4 | 浏览器打开 `http://<host>:9200/`（消息中心 SPA 由本进程托管） | 页面可加载、消息列表可见 |
| 5 | （用了外发渠道）查投递：`GET /nfy/api/v1/admin/deliveries`（租户 token） | 能查到投递行；引擎日志行见 §3.6-3 |

## 8. 升级

跨版本升级步骤、兼容口径与 Flyway 注意事项（空库要求 / checksum 校验 / 历史脚本禁改）统一见
[../07-版本与支撑/upgrade-guide.md](../07-版本与支撑/upgrade-guide.md)；版本变更明细见仓内 `CHANGELOG.md` 与
`docs/release/release-notes.md`。
