# 最佳实践 · 故障排查手册（诊断流程 / 症状索引 / 日志追踪 / DB 直查）

> notification4j 使用手册 · 06-最佳实践第 3 篇 · 版本口径 **v1.4.0** × framework4j **v1.7.1**。
> 面向三类排查者：业务方后端（信封 code / token）、宿主前端（握手 / 会话）、自托管运维（引擎 / 渠道 / DB）。
> 文案、错误码、键名、表列名逐项核对自已评审各篇与 starter 源码、app yml、`db/migration/V1.0.0__init_nfy_schema.sql`、
> docs/test/report 各轮实测。错误码字典见 [error-codes.md](../04-参考/error-codes.md)；运维面见
> [operations.md](../05-部署与运维/operations.md)；Q&A 型问题见 [faq.md](../07-版本与支撑/faq.md)。

**先记两条 HTTP 语义铁律**（详见 [error-codes.md §2](../04-参考/error-codes.md)）：

1. 业务错误绝大多数是 **HTTP 200 + 信封 code**——auth 端点凭据错 / 防爆破锁定也是 HTTP 200 +
   信封 `401`/`429`。**只盯 HTTP 4xx 做告警会漏报**，分支逻辑与监控都应以信封 code 为准。
2. HTTP 直写仅四种：**域闸 403 / 签名 401 / 限流 429 / 幂等 409**——body 仍是同一信封，解析无需特判。

## 1. 诊断总流程：四步定位

```mermaid
flowchart LR
    A[异常现象] --> B["① 读信封 code<br/>（HTTP 200 也要读）"]
    B --> C["② 记 trace_id<br/>body 字段 + X-Trace-Id 头"]
    C --> D["③ 探健康端点<br/>/nfy/api/v1/ops/health"]
    D --> E["④ 日志 grep + DB 直查<br/>（本文 §3 / §4）"]
    E --> F[按 §2 症状索引对症处置]
```

| 步 | 动作 | 看什么 / 出口 |
|---|---|---|
| ① | 读响应信封 `code`（勿只看 HTTP 状态码） | `0`=成功；非 0 按段位查 [error-codes §3](../04-参考/error-codes.md)；`error[]` 的 `field` 定位参数字段。多数接入问题到此即定位 |
| ② | 记录 `trace_id` | 信封 body 与 `X-Trace-Id` 响应头双通道，不传由服务端生成；报障（GitHub Issue / 工单）必附 trace_id + 时间点，运维凭它在 tracelog 控制台反查全链日志（§3.1） |
| ③ | 探健康端点 `GET /nfy/api/v1/ops/health`（免鉴权） | **DOWN 时 HTTP 仍 200、信封 code 仍 0——判健康只看 `data.status` 与 `checks.db/redis`**。`db DOWN`=PG 不可达 / 凭据错 / Druid 池取不到连接；`redis DOWN`=token / 幂等 / 限流 / 防爆破全不可用，应先摘除流量（[operations §1](../05-部署与运维/operations.md)） |
| ④ | 日志 grep（§3.2 真实文案）+ DB 投递表直查（§4 只读 SQL） | 定位引擎 / 渠道 / 订阅 / 配置层；站外投递问题以 `nfya_delivery` 行的 `status / retry_count / next_retry_at / error_message` 为准 |

调用方侧判定顺序（三步，[error-codes §1](../04-参考/error-codes.md)）：先 body 信封 `code` →
`code` 过宽再读 `error[]`（`field` / 子码 / `rejectedValue`）→ HTTP 状态码只作网关与监控分层。
探活脚本示例（**必须解析 body 而非 HTTP 状态码**）：

```bash
curl -s http://localhost:9200/nfy/api/v1/ops/health | grep -q '"status":"UP"' || echo "ALIVE 失败"
```

## 2. 症状索引主表

> 列「详见」指向已评审各篇的对应节；错误码语义以 [error-codes.md](../04-参考/error-codes.md) 为准。
> 用法：先按 §1 ① 拿到信封 code / 症状表象 → 在本表按行对号 → 按「验证」列取证 → 按「处置」列动作。
> 同一表象多因时（如「消息没收到」），按表中列出的顺序逐一排除。

| 症状 | 最可能原因 | 验证 | 处置 | 详见 |
|---|---|---|---|---|
| 启动即退：机密 env 缺失（独立部署） | `JWT_SECRET`（≥32 字符）/ `AES_KEY` / `PLATFORM_CLIENT_SECRET` 无默认占位，缺失 fail-fast | 启动日志首屏占位符解析失败 | 注入三 env 重启 | [deployment §3.1](../05-部署与运维/deployment.md) |
| 启动报端口占用 | `server.port: 9200` 被占 | 日志端口冲突行 | `--server.port=` 换端口或释放占用 | [operations §8](../05-部署与运维/operations.md) |
| 启动报数据源 / 迁移失败 | PG 不可达 / 凭据错；**flyway 与业务池两处 url 只改了一处**；库非空被 `baseline-on-migrate=false` 拒绝 | Flyway 连接日志；是否指向了带无关对象的「脏」库 | 两处 url 都覆盖；非空库先人工清理（升级勿手改历史迁移脚本，`validate-on-migrate: true` 会中止） | [deployment §3.2/§3.4](../05-部署与运维/deployment.md) |
| 切 profile 后「进程在跑、业务全断」 | app yml 全部 `framework4j.*` / `nfy.*` 位于 dev 段，`--spring.profiles.active=prod` 后 dev 段整体失效，framework4j 数据面闸（缺省 false）不开 | 进程无业务 DataSource/Redis；健康端点 checks DOWN | 生产 profile 须自带等价配置段（`spring.config.import` / 环境变量 / 命令行） | [deployment §3.2](../05-部署与运维/deployment.md)、[configuration §4](../05-部署与运维/configuration.md) |
| 嵌入宿主启动失败 / API 全 404 | 启动失败「`local 门面需要数据面：nfy.data.enabled=true 且扫描 fun.commons.notification4j.mapper`」=数据闸关或缺 `@MapperScan`；API 404=`enable-api=true` 但数据闸关（启动期有 WARN，见 §3.2） | 日志逐字搜该失败文案 / WARN 行 | 开 `nfy.data.enabled=true`、启动类补 `@MapperScan("fun.commons.notification4j.mapper")` | [quickstart-embed §5/§8](../02-快速开始/quickstart-embed.md) |
| HTTP 403（信封 403）或信封 10300 | 域闸：token 身份域 ≠ 端点域（TENANT↔PLATFORM 互打、claim 缺失）；10300「令牌类型不匹配」=SERVICE 型 S2S JWT 打 TENANT 端点（client-starter 默认装配注入） | 解码 token 看 type claim / `tenant_id` 是否为 0 | 换对身份域的 token；remote 接入按 AUTH-001 换 TENANT token 作供源 | [auth §1.1](../03-开发指南/auth.md)、[quickstart-remote §6](../02-快速开始/quickstart-remote.md) |
| 换 token 信封 401 / 429 | 401=凭据错、租户不存在、**租户非 ACTIVE** 三态同码防探测；429=同 client_id 连错 5 次锁 15min（锁定期间正确凭据也被拒，无 Retry-After） | `client_id` 是否租户 open_id（非 name）；租户态找平台确认 | 修凭据重试（勿密集重试防锁）；等解锁或由成功清零 | [auth §2.3/§5](../03-开发指南/auth.md) |
| 信封 10201 / 10205 频繁出现 | token 8h 硬到期 / 静置 30min 滑动过期 / 多实例共用同一租户凭据互踢（10205「账号已在别处登录」） | 谁还在用同一凭据换 token | 每个接入方独立租户，或错峰重换（到期前 5min） | [auth §4](../03-开发指南/auth.md) |
| 信封 10101「X-User-Id 不能为空」 | T+U 端点（MSG-004~008、ANN-001~003、CHN-001~005、SUB-001/002）缺 `X-User-Id` 头；remote 模式 `RemoteNotifyClient` 不注该头（已知限制） | 对照端点族表核对 | 补头；remote 按用户视角走自定义 transport 或 SPA 面 | [auth §3](../03-开发指南/auth.md)、[quickstart-remote §5](../02-快速开始/quickstart-remote.md) |
| HTTP 401 + 信封 10101/10102/10302 | 签名面四态：缺四头「签名头缺失」/ 时间窗「签名时间戳过期或超前（容忍 ±300s）」/ nonce 重放 / 签名值不匹配——仅在服务端加了 `framework4j.signature.path-patterns` 后出现（出厂空=不校验；client-starter 凭据两键缺一也不注签名，见 §3.2 WARN 行） | 调用方时钟 NTP；签名串五要素（METHOD\nPATH\nTS\nNONCE\nBODY_MD5）；nonce 是否复用 | 按 signature §3.1 五步表逐项修 | [signature §3.1](../03-开发指南/signature.md) |
| 信封 10401「业务号重复，幂等拒绝」 | 同租户同 `biz_no` 已受理（uk 真闸）；撤回后同号重发同样 10401 | MSG-003 `send-results/{biz_no}` 查首次结果 | **原样重发即拿回首次结果**；要发新内容必须换 biz_no | [sending §3/§9](../03-开发指南/sending.md) |
| HTTP 409 + 信封 10501 | `Idempotency-Key` 同 key 异 body（「请勿重复提交」）或前一请求仍在处理 | key 是否跨请求复用、body 是否变过 | 同 key 同 body 会回放首次成功响应（HTTP 200）；换 key 重发 | [error-codes §3.5](../04-参考/error-codes.md) |
| HTTP 429 + 信封 10500 | 限流：出厂 100 次/分钟，**实际按客户端 IP**（每路径独立桶；NAT 后多用户共桶） | 响应头 `Retry-After` 与 `X-RateLimit-*` | 按 Retry-After 退避；持续触顶调 `framework4j.rate-limit.default-limit` | [configuration §9](../05-部署与运维/configuration.md) |
| 消息发了、用户没收到（站外） | 四因：引擎未开（`nfy.runtime.engine.enabled` 缺省 false）/ 订阅矩阵未勾或渠道非 ENABLED（计划期即过滤）/ 渠道熔断 DISABLED（投递行留原地不被领取）/ quiet_hours 推迟（`next_retry_at`=窗结束，推迟非丢弃） | DLV-001 或 §4 SQL 查行状态：PENDING+`next_retry_at` 在未来=等窗或等渠道；日志有无 `[Engine] 外发引擎已启动` | 开引擎；用户勾选渠道；恢复渠道（下行）；URGENT / INAPP 天然豁免免打扰 | [sending §4](../03-开发指南/sending.md)、[operations §8](../05-部署与运维/operations.md) |
| EMAIL 投递全失败 | `mail-host` 为空（error_message 逐字 `SMTP 未配置(nfy.engine.mail-host)`，走重试→DEAD）；`SMTP 失败:<异常类名>`=认证错 / 网关拒收 / STARTTLS 不被支持 | DLV-001 查 `error_message` 原文 | 配 `nfy.runtime.engine.*` SMTP 五键并重启；核对端口与凭据 | [operations §4.1](../05-部署与运维/operations.md) |
| webhook 渠道注册被拒 10609 | 非 https / 非 443 / 域名不在官方白名单（`oapi.dingtalk.com`、`qyapi.weixin.qq.com`、`open.feishu.cn`、`*.feishu.cn`）/ DNS 解析出内网或失败（出口 fake-IP DNS 也会中招） | 机器人地址域名与 DNS 出口 | 只用官方机器人地址；生产要求直连 DNS 解析到公网 | [channels §2.2](../03-开发指南/channels.md) |
| 渠道启用被拒 10610 / verify 报 10604 | 熔断态（连续失败 `fail_count≥5` 置 DISABLED）未重新验证；EMAIL 的 verify 恒 10604 属设计（首次投递时校验，非故障） | 渠道列表 `status` / `fail_count`；日志 `[Engine] 渠道 … 已熔断 DISABLED` | IM：先 verify 成功再 patch ENABLED；EMAIL：patch ENABLED 即重新验证并清零 fail_count；**无自动恢复** | [channels §5](../03-开发指南/channels.md)、[operations §3.4](../05-部署与运维/operations.md) |
| 前端一直「正在连接…」或「会话已失效，正在重新连接…」不恢复 | origin 不在双面白名单（构建时 `VITE_NFY_PARENT_ORIGINS` ∪ 运行时 `oem.hosts`，fail-closed 静默忽略）；或宿主只投一次 token、无 replay（token 过期后不自愈） | Network 面板看 `/runtime/oem/hosts` 核验调用及返回；origin 精确匹配（scheme+host+port） | 平台域 PATCH `oem.hosts` 补宿主域；宿主每次收到 NFY_READY 重新代换回投 | [frontend-embed §4/§9](../03-开发指南/frontend-embed.md) |
| 公告发不出 | 10611：同租户同时生效公告已达 20（**未来生效窗口的已发布也占名额**）；10402：非草稿态不可发布 / 已下线或未发布 / 公告已失效 | AAN-001 列表按 `status=PUBLISHED AND expire_at>now` 计数 | 先 offline 旧公告再发布；按状态机走 DRAFT→PUBLISHED→OFFLINE | [announcements §2/§5](../03-开发指南/announcements.md) |
| 批量任务「卡住」 | RUNNING 未到终态（无推送，须 JOB-001 以 2s 量级轮询）；`type_code` 不存在时异步 FAILED（受理仍 code=0，`failed_items[]` 截断 ≤100）；job 状态存 Redis **TTL 48h**，过期一律 10400「任务不存在或已过期」 | `GET /nfy/api/v1/runtime/jobs/{job_id}` 看 status / failed_items | 先建好类型再换 biz_no 重发；终态 DONE / PARTIAL / FAILED | [sending §6](../03-开发指南/sending.md) |

## 3. 日志与追踪

### 3.1 trace_id 三贯通怎么用

| 通道 | 形态 | 出处 |
|---|---|---|
| ① 响应信封 | 每个响应 body 携带 `trace_id` | fwk4j-web `ApiResponse` |
| ② 响应头 | `X-Trace-Id`：请求可带（带则透传），响应必返，不传由服务端生成 | api-spec 公共头表 |
| ③ SQL 注释 | Druid Filter 注入 `/*traceid=xxx*/` 注释——PG 侧日志 / 慢 SQL 直接按 trace_id 关联 | app yml `framework4j.sql-tracing.enabled=true, mode=ALL`（三模式 DISABLED/WRITE_ONLY/ALL） |

- **日志位置**：出厂无文件 appender，全部走 stdout——由 systemd/journald、`docker logs`、K8s 日志体系收集。
- **tracelog 控制台**：`http://<host>:9200/tracelog/index.html` 按 traceId / URL / userId 临时提权 DEBUG、聚合日志
  （静态页免鉴权，其 API 需 OPS token）；容器化注意 Redis 故障降级目录 `framework4j.tracelog.collection.fallback-dir`
  （出厂 `/tmp/notification4j-tracelog-fallback`）需挂载。
- **落库口径（如实）**：`nfyp_audit_log.trace_id`（审计动作落库）可反查；`nfya_delivery` 虽有 `trace_id` 列
  （DDL varchar(68)），但计划期恒写空串（`DeliveryPlanService#insertRows` `setTraceId("")`）——**不能按投递行反查
  trace_id**，投递排查请走 `biz_no` / `delivery_id`（§4）。
- **报障口径**：GitHub Issue / 平台工单必附 `trace_id`（或 `X-Trace-Id`）+ 大致时间点 + 复现步骤；
  安全漏洞走 SECURITY.md 渠道，勿开公开 issue（[手册 README §5](../README.md)）。

### 3.2 grep 关键字清单（均为源码 / 实测逐字文案）

| grep 关键字 | 含义 | 出处 |
|---|---|---|
| `[Engine] 外发引擎已启动` | 引擎已开（行内回显 scan/batch/reaper 参数）；**没有此行=引擎未开** | `DeliveryEngine#start` |
| `[Engine] 渠道 ` + ` 已熔断 DISABLED`（整行形如 `[Engine] 渠道 {id} 连续失败 {n} 次，已熔断 DISABLED`） | 连续失败 ≥5 触发熔断（属主还会收到标题「渠道连续失败已自动停用」的 CHANNEL_ALERT 站内信，biz_no 前缀 `channel-break-`） | `DeliveryEngine` |
| `[Engine] reaper 回收 SENDING 超时行` | 卡死 SENDING 行被回收为 PENDING（at-least-once） | `DeliveryEngine` |
| `[Engine] 投递异常 delivery=` | 单行投递异常（外呼失败留痕） | `DeliveryEngine` |
| `[Delivery] quiet_hours jsonb 解析失败(fail-open 即时投递)` / `[Delivery] quiet_hours 时刻解析失败(fail-open 即时投递)` | 免打扰脏数据 fail-open（宁可打扰不丢消息） | `DeliveryPlanService` |
| `[Channel] 验证外呼异常 channel=` | verify 外呼异常（连接失败等） | `ChannelVerifier` |
| `[nfy] nfy.data.enabled=false 且 nfy.runtime.enable-api=true` | 嵌入宿主 API 将全 404 的启动期告警 | `NfyAutoConfiguration` |
| `[AuthenticatedHttpTransport] remote-tenant-id/secret 未配, 跳过签名` | client-starter 租户凭据两键缺一，签名与 Bearer 均不注入 | `AuthenticatedHttpTransport` |
| `Failed to configure a DataSource`（嵌入宿主） | `framework4j.datasource.enabled` 缺省 false，原生装配向 `spring.datasource.*` 要 url | Spring Boot 原生报错（quickstart-embed §8） |
| 启动日志「单池三零」：原生 Druid wrapper 计 0、HikariPool 计 0、ERROR 计 0 | 数据面已被 starter `NfyDataPlaneTakeoverFilter` 接管的正常口径；出现非零即装配异常 | deployment §3.6 / 测试报告 |
| `SMTP 未配置(nfy.engine.mail-host)`、`SMTP 失败:` | **投递行 error_message 值**（DLV-001 / §4 SQL 可见，非日志行） | `EmailSender` |
| `sql injection violation` | druid wall 拦截 PG 方言（v1.2.2 前历史坑，见 §5） | 测试报告 2026-09-17-01 |

## 4. DB 直查口径（只读排查 SQL）

> 只读排查，勿手工 UPDATE / DELETE 投递与渠道行（状态机由服务端维护；干预走 DLV-002 重投与 verify/patch 接口）。
> 表 / 列名核对自 `backend/notification-spring-boot-starter/src/main/resources/db/migration/V1.0.0__init_nfy_schema.sql`；
> 连接与凭据见 [deployment §3.2](../05-部署与运维/deployment.md)。注意 `nfya_delivery.target` 是**脱敏快照**
> （原地址不可见）；活跃行由部分索引护航，历史行增长不放大扫描成本。

```sql
-- ① 投递状态分布：PENDING 积压（引擎停摆信号）/ FAILED 待重试 / DEAD 存量 / SENDING 卡死
SELECT status, count(*) FROM nfya_delivery GROUP BY status;

-- ② DEAD 按渠道分布（配合熔断日志行与下方渠道表 fail_count）
SELECT channel_type, count(*) FROM nfya_delivery
WHERE status = 'DEAD' GROUP BY channel_type;

-- ③ 某条消息的全部投递行（biz_no → message → delivery；source_type 判别来源）
SELECT d.id, d.userid, d.channel_id, d.channel_type, d.status,
       d.retry_count, d.next_retry_at, d.sent_at, d.error_message
FROM nfya_delivery d
JOIN nfya_message m ON m.id = d.source_id AND m.tenant_id = d.tenant_id
WHERE d.tenant_id = :tenant_id AND m.biz_no = :biz_no
  AND d.source_type = 'MESSAGE' AND d.is_deleted = 0;
-- 公告外发：source_type 换 'ANNOUNCEMENT'，改 JOIN nfya_announcement（按其 id / biz_no）

-- ④ 非 ENABLED 渠道（PENDING 未验证 / DISABLED 熔断或人工停用；fail_count≥5 即曾熔断）
SELECT id, scope, userid, channel_type, status, fail_count, last_verify_at
FROM nfya_channel
WHERE tenant_id = :tenant_id AND status <> 'ENABLED' AND is_deleted = 0;

-- ⑤ 用户订阅矩阵与免打扰（「某渠道没收到」时核对勾选与静默窗）
SELECT type_code, channel_ids, quiet_hours
FROM nfya_subscription
WHERE tenant_id = :tenant_id AND userid = :userid AND is_deleted = 0;

-- ⑥ 站内信可见性：消息主档（status=SENT；撤回后 CANCELLED，用户侧即不可见）
SELECT id, biz_no, type_code, level, status, receiver_count, created_at
FROM nfya_message
WHERE tenant_id = :tenant_id AND biz_no = :biz_no AND is_deleted = 0;

-- ⑦ 某消息的接收行（「用户说没看到站内信」：确认 recipient 行存在与已读态）
SELECT r.userid, r.read_status, r.read_at, r.created_at
FROM nfya_message_recipient r
JOIN nfya_message m ON m.id = r.message_id AND m.tenant_id = r.tenant_id
WHERE r.tenant_id = :tenant_id AND m.biz_no = :biz_no AND r.is_deleted = 0;
```

判读口径（与引擎语义对齐，[operations §3](../05-部署与运维/operations.md)）：

- 状态机 `PENDING → SENDING → SUCCESS | FAILED`（FAILED 按 1/5/15min 退避回可扫）；重试超限（`MAX_RETRY=3`，
  第 4 次失败）→ `DEAD` 终态，唯一出路是 DLV-002 人工重投（仅 DEAD，非 DEAD 报 10402；重投后
  **retry_count 不清零**，渠道未修复则一次失败即回 DEAD）。
- 引擎领取只 JOIN `status='ENABLED'` 的渠道（`NfyaDeliveryMapper#claimBatch`，`FOR UPDATE OF d SKIP LOCKED`）：
  熔断 / 停用渠道的投递行**留原地不计重试**，先恢复渠道再等下一轮扫描。
- `next_retry_at` 在未来：正常（退避未到点 / quiet_hours 窗结束时刻 / 引擎限速归还延后）；配合 §3.2 日志判断。
- PENDING 积压持续增长：引擎停摆（`enabled` 被关 / worker 不够 / 渠道全 DISABLED）——先确认启动行，
  再按 [operations §2](../05-部署与运维/operations.md) 调 `batch-size` / `worker-count`（改配置须重启，无热更）。
- DEAD 存量突增：优先看平台域概览 `dead_count` 与 `dead_tenants`（DEAD 最多租户 Top ≤20）圈定租户，再走
  DLV-001/002 定位与重投（[operations §7](../05-部署与运维/operations.md)）。

**干预动作走 API 面（勿直改 DB）**：

| 动作 | 端点 | 语义 |
|---|---|---|
| 投递查询 | `GET /nfy/api/v1/admin/deliveries?biz_no=&status=...`（DLV-001，租户 token） | 过滤分页查投递行（`total` 为租户全量口径，不随过滤收窄） |
| 人工重投 | `POST /nfy/api/v1/admin/deliveries/{delivery_id}/retry`（DLV-002） | 仅 DEAD；回 PENDING、`next_retry_at=now`，`retry_count` 不清零 |
| 渠道重验证 | `POST /nfy/api/v1/runtime/channels/{channel_id}/verify`（CHN-003） | IM 熔断恢复第一步（失败 10604 带摘要）；EMAIL 恒 10604 |
| 渠道启停 | `PATCH /nfy/api/v1/runtime/channels/{channel_id}` | IM 未验证 / 熔断态启用 10610；EMAIL patch ENABLED 即重验证并清零 fail_count |

## 5. 历史坑对照表（v1.2.2 已修——旧版本升级提示）

> 三项均为 v1.2.2 缺陷台账修复，测试报告 2026-09-17-01 有修复前后实测数据；**仍在 v1.2.1 及更早版本的自托管
> 实例必中**。跨版本升级步骤统一见 [upgrade-guide.md](../07-版本与支撑/upgrade-guide.md)。

| 坑 | 影响版本 | 症状（实测） | 根因 | 修复 |
|---|---|---|---|---|
| D-3 druid wall 拦 SKIP LOCKED | < v1.2.2 | 外发引擎每轮扫描必炸（出厂实例 40 秒 32 次 `sql injection violation`），投递永不发生；Flyway 部分索引 WHERE 同样被拦 | 出厂 Druid `filters` 默认含 `wall`，不识别 PG 方言（`FOR UPDATE OF d SKIP LOCKED` / 部分索引） | v1.2.2 出厂 `filters: stat,slf4j`；如需 wall 先在有网环境验证引擎与迁移链路全通再加回 |
| D-4 redisson 落 6379 | < v1.2.2 | 已配 `framework4j.redis` host/port，进程仍连 `127.0.0.1:6379`（实测 25 连接） | redisson-spring-boot-starter 自动装配端点取 `spring.data.redis` 默认值，不随数据源配置覆盖 | v1.2.2：exclude + 数据源级 `redisson.enabled: false`；v1.3.0 起由 `NfyDataPlaneTakeoverFilter` veto 原生装配 |
| F-1 前端 Invalid Date | < v1.2.2 | 消息中心全站时间显示 Invalid Date（Messages / Announcements / Bell 等） | 后端 Long→String 契约（防 JS 精度），前端 `new Date(字符串)` 不解析 | v1.2.2 新增 `fwkTime.ts` `parseFwkTime`（数字字符串→Number→Date，<1e12 按秒级；非法→null），四页解析点统一改走 |

另有**获取层**历史坑：v1.2.2 及更早 tag 在 JitPack 构建失败（`jitpack.yml` 晚于该 tag 入库，默认 JDK 8 构建链
跑不动 Spring Boot 3.x）——请直接用 v1.3.0+，详见 [faq.md Q17](../07-版本与支撑/faq.md)。

**版本自查**：独立部署看产物名 `notification4j-app-<版本>.jar`（当前 1.4.0）；嵌入 / 跨进程看依赖坐标
（JitPack `com.github.funcommons.notification4j:notification4j-starter:v1.4.0` / 内部仓
`fun.commons.notification4j:notification4j-starter:1.4.0`）；版本变更明细看 `CHANGELOG.md` 与
[release-notes](../../release/release-notes.md)。

---

> 取证源：手册已评审各篇（quickstart-embed / quickstart-remote / auth / signature / sending / announcements /
> channels / frontend-embed / error-codes / configuration / deployment / operations）、[api-spec §6](../../api/api-spec.md)、
> starter 源码（`DeliveryEngine` / `DeliveryPlanService` / `NfyaDeliveryMapper` / `EmailSender` / `WebhookTargetValidator` /
> `AnnouncementAdminService` / `NfyOpsHealthController` / `NfyAutoConfiguration` / `AuthenticatedHttpTransport`）、
> app `application.yml`、`db/migration/V1.0.0__init_nfy_schema.sql`、`CHANGELOG.md`、
> docs/test/report/（2026-09-17-01 / 09-18-01 / 09-19-01 / 09-21-01）。
