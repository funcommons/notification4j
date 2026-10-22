# 快速开始：5 分钟发出第一条站内信（独立部署 · 纯 curl）

> 适用版本 **v1.4.0**（fat jar `notification4j-app-1.4.0.jar`）。全程 curl、零代码：
> 起中间件 → 起服务 → 换平台 token → 建租户 → 建类型 → 发消息 → 查到它。
> 文中每个路径、请求体、配置键均取自仓内 E2E（`frontend/e2e-regression/`）与集成测试（`backend/notification4j-it/`）真实代码；
> 示例响应中的具体值均为**示例值**。统一响应信封 `code=0` 即成功（另含 `message/data/trace_id` 等公共字段，示例仅列关键项）。

## 前置

| 项 | 要求 |
|---|---|
| JDK | 17+（构建与运行 fat jar） |
| Docker | 本机可用（拉起 PostgreSQL 16 / Redis 7） |
| Maven | 3.6+（依赖 framework4j v1.7.1，经 JitPack 拉取） |
| curl | 任意近版本 |

## 第 1 步：起中间件（PG16 + Redis7）

出厂配置指向 `localhost:5432/notification4j`（用户 `admin` / 密码 `test@2026`，开发默认）与 `localhost:6379`
（出处：`backend/notification4j-app/src/main/resources/application.yml`）。容器按下面对齐出厂默认，服务零覆盖即可启动：

```bash
docker run -d --name nfy-pg \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=test@2026 -e POSTGRES_DB=notification4j \
  -p 5432:5432 postgres:16

docker run -d --name nfy-redis -p 6379:6379 redis:7-alpine
```

说明：镜像 tag 与集成测试 Testcontainers 一致（`NfySmokeTest`）；建表无需手工执行（首启 Flyway 自动迁移，
出厂 `baseline-on-migrate=false` 要求**空库**）；JDBC 串携带 `?stringtype=unspecified`（JSONB 写入所需），
由服务端出厂 url 自带，无需额外操作。

## 第 2 步：构建并启动服务（fat jar）

```bash
cd backend
mvn install -DskipTests        # 首次构建四模块并安装 starter（首次勿加 -o：framework4j v1.7.1 需从 JitPack 拉取）
# 产物：notification4j-app/target/notification4j-app-1.4.0.jar（可执行 fat jar，前端 SPA 已内嵌）
```

三个机密环境变量**无默认值、缺失即启动失败**（fail-fast）：

| 环境变量 | 用途 | 约束 |
|---|---|---|
| `JWT_SECRET` | access-token 签名密钥 | ≥32 字节 |
| `AES_KEY` | 敏感列 AES 加密密钥 | 32 字节 |
| `PLATFORM_CLIENT_SECRET` | 平台域 client 换 token 密钥 | - |

（`HASH_SALT` 缺省 `salt`、`PLATFORM_CLIENT_ID` 缺省 `PLATFORM`，可不设。）

```bash
# 示例密钥取自 E2E 启动脚本 frontend/e2e-regression/bin/start-app.sh（仅本地演示用，生产必须换随机值）
JWT_SECRET=e2e-jwt-secret-e2e-jwt-secret-e2e-123456 \
AES_KEY=12345678901234567890123456789012 \
PLATFORM_CLIENT_SECRET=platform-secret-e2e \
java -jar notification4j-app/target/notification4j-app-1.4.0.jar
```

- 服务端口 **9200 为出厂默认**（`server.port: 9200`；E2E 同端口零覆盖）。
- 换端口/换库/复用 E2E 容器（PG 25432 / Redis 26379）时的覆盖参数（E2E `start-app.sh` 同款）：

```bash
DS="jdbc:postgresql://localhost:25432/notification4j?stringtype=unspecified"
java -jar notification4j-app-1.4.0.jar \
  --spring.flyway.url="$DS" \
  --framework4j.datasource.datasources.default.url="$DS" \
  --framework4j.redis.datasources.default.port=26379
```

## 第 3 步：健康冒烟（无鉴权）

```bash
curl -s http://localhost:9200/nfy/api/v1/ops/health
```

路径 `/nfy/api/v1/ops/health` 在出厂配置的鉴权放行名单内（`access-token.exclude-path-patterns`）；
探针为 db=`SELECT 1`、redis=`PING`。返回 `data.status=UP` 即双中间件连通：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "status": "UP",
    "checks": { "db": { "status": "UP" }, "redis": { "status": "UP" } }
  },
  "trace_id": "b7e1c2f3（示例）",
  "fail": false
}
```

## 第 4 步：换平台 token（client_credentials）

```bash
export PLATFORM_CLIENT_SECRET=platform-secret-e2e    # 与第 2 步启动时注入的值一致

PLATFORM_TOKEN=$(curl -s -X POST http://localhost:9200/nfy/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=PLATFORM&client_secret=$PLATFORM_CLIENT_SECRET" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

echo "$PLATFORM_TOKEN"    # 应输出一段 JWT（示例：eyJhbGciOiJIUzI1NiJ9...）
```

- 端点与表单字段出处：E2E `helpers/nfy.ts` 的 `mintToken()`、集成测试 `NfyPlatformDomainTest.platformToken()`。
- `client_id=PLATFORM` 是出厂平台域字面量（`framework4j.tenant.platform.client-id` 缺省值）；token 有效期出厂 8 小时（`expire-seconds: 28800`）。

## 第 5 步：建租户（平台域 PTE-001，密钥仅返回一次）

```bash
CREATE_RESP=$(curl -s -X POST http://localhost:9200/nfy/platform/api/v1/tenants \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"demo-tenant","email":"demo-2026@example.com","privileges":{"signature":false},"config":{"retentionDays":30}}')

echo "$CREATE_RESP"
```

请求体取自 E2E `helpers/nfy.ts` 的 `createTenant()`。`open_id` / `tenant_secret` **仅此一次**明文返回（不返回内部数字 id）：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "open_id": "K3xd9QzW7pRb（示例，12 字符）",
    "tenant_secret": "f8c1e2a9（示例，请立即保存）"
  },
  "trace_id": "（示例）",
  "fail": false
}
```

用返回的凭据换**租户 token**（后续所有租户域操作都用它）：

```bash
OPEN_ID="K3xd9QzW7pRb"            # ← 换成上一步真实返回值
TENANT_SECRET="f8c1e2a9"          # ← 同上

TENANT_TOKEN=$(curl -s -X POST http://localhost:9200/nfy/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=$OPEN_ID&client_secret=$TENANT_SECRET" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
```

## 第 6 步：建消息类型 → 发第一条消息

发送会校验类型存在（`10601 消息类型不存在或已停用`），**最短路径是先用租户 token 建一个类型**（E2E `l2-messages.spec.ts` 的 `createType()` 同款）：

```bash
curl -s -X POST http://localhost:9200/nfy/api/v1/admin/types \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"QUICKSTART_DEMO","name":"快速开始演示","default_channels":["INAPP"]}'
```

```json
{ "code": 0, "message": "操作成功", "data": { "type_id": "1943000000000000001（示例，雪花数字串）" }, "fail": false }
```

发消息（发送面只需租户 token，无需 `X-User-Id`）：

```bash
curl -s -X POST http://localhost:9200/nfy/api/v1/runtime/messages \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"QUICKSTART_DEMO","user_ids":["u_1001"],"title":"订单支付成功","content":"订单 **OD-123** 已支付","biz_no":"qs-demo-1"}'
```

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "message_id": "1943000000000000002（示例，雪花数字串）",
    "biz_no": "qs-demo-1",
    "receiver_count": 1,
    "inapp_saved": true,
    "delivery_planned": 0
  },
  "trace_id": "（示例）",
  "fail": false
}
```

口径说明（`level` 与 INAPP 落库即达）：

- `level` 可省略，缺省落库为 `NORMAL`；显式取值 `NORMAL / IMPORTANT / URGENT`。`biz_no` 缺省时服务端生成 UUID。
- 类型只配 `INAPP` 渠道时**落库即达**：`inapp_saved=true`、`delivery_planned=0`（不产生站外投递行），未读数立即可查。
- `biz_no` 是租户内幂等闸：同租户同 `biz_no` 重发返回 `10401`。

## 第 7 步：验证——查到这条消息

查询面按「租户 token + 用户头」口径，必须带 `X-User-Id`（缺失返回 `10101`）。

（1）未读数（未读 = 站内信未读 + 公告未确认合成）：

```bash
curl -s http://localhost:9200/nfy/api/v1/runtime/messages/unread-count \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
```

```json
{ "code": 0, "message": "操作成功", "data": { "unread_count": "1", "unconfirmed_count": "0", "total": "1" }, "fail": false }
```

> 计数字段为字符串（framework4j 的 Long→String 序列化契约），按数字解析即可。

（2）未读列表（Cursor 分页）：

```bash
curl -s "http://localhost:9200/nfy/api/v1/runtime/messages?read_status=UNREAD" \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
```

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "list": [
      {
        "message_id": "1943000000000000002（示例）",
        "title": "订单支付成功",
        "type_code": "QUICKSTART_DEMO",
        "level": "NORMAL",
        "read_status": "UNREAD",
        "created_at": "1759142400000（示例，毫秒时间戳字符串）"
      }
    ],
    "next_cursor": null,
    "has_more": false
  },
  "fail": false
}
```

（3）详情即已读（MSG-005 口径：查详情返回的同时置为已读，再查未读数应为 0）：

```bash
curl -s http://localhost:9200/nfy/api/v1/runtime/messages/1943000000000000002 \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# 再执行 (1)，unread_count 应变为 "0"
```

## 第 8 步：浏览器看一眼 + 清理

- 浏览器打开 `http://localhost:9200/` 即消息中心控制台（SPA 由 fat jar 直接托管，OpenAPI 在 `/swagger-ui/index.html`）。
  把消息中心**嵌入你自己的应用页面**（铃铛 + 五页），见 [quickstart-frontend.md](quickstart-frontend.md)。

清理（按需）：停服务 `kill <java 进程号>`（优雅停机，SIGTERM 后最多等 30s 处理完在途请求）；拆容器：

```bash
docker rm -f nfy-pg nfy-redis
```

## 下一步

- 嵌入你自己的 Spring Boot 应用（进程内发消息，零 HTTP）：[quickstart-embed.md](quickstart-embed.md)
- 消息中心前端嵌入与握手协议：[quickstart-frontend.md](quickstart-frontend.md) · 完整契约：[../../api/api-spec.md](../../api/api-spec.md) · 生产部署：[../05-部署与运维/deployment.md](../05-部署与运维/deployment.md)
