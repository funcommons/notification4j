# 运维手册：健康、引擎、投递与容量

> notification4j 使用手册 · 05-部署与运维第 2 篇 · 版本口径 **v1.4.0**。面向独立部署实例（fat jar）的日常运维。
> 端点 / 参数 / 日志文案 / DDL 索引均核对自仓内源码：`NfyOpsHealthController`、`NfyProperties`、`DeliveryEngine`、
> `DeliveryAdminService` / `NfyAdminDeliveryController`、`ChannelCoreService` / `ChannelVerifier` / `EmailSender`、
> `StatsService`、app yml 与 `db/migration/V1.0.0__init_nfy_schema.sql`。部署与配置见
> [deployment.md](deployment.md)；API 契约以 [api-spec](../../api/api-spec.md) 为唯一事实源。

## 1. 健康检查

- 端点：`GET /nfy/api/v1/ops/health`（**免鉴权**——app yml `access-token.exclude-path-patterns` 放行；供 LB/K8s 探活）。
- 探针实现（`NfyOpsHealthController`）：db = `SELECT 1`（经 SqlSessionFactory 取连接）、redis = `PING`；任一 DOWN → 整体 DOWN。

```json
{ "code": 0, "message": "操作成功",
  "data": { "status": "UP", "checks": { "db": { "status": "UP" }, "redis": { "status": "UP" } } },
  "trace_id": "（示例）", "fail": false }
```

| 字段 | 判读 |
|---|---|
| `data.status` | **判健康只看这里**。注意：DOWN 时 HTTP 仍为 200、信封 `code` 仍为 0（控制器恒走 success 信封），探活脚本必须解析 body |
| `checks.db.status=DOWN` | PG 不可达 / 凭据错 / 连接池取不到连接（Druid `max-wait=1000`，1s 内拿不到即探针失败） |
| `checks.redis.status=DOWN` | Redis 不可达或连接工厂缺失；token 校验 / 幂等 / 限流 / 防爆破全部依赖它，DOWN 即功能面不可用，应摘除流量 |

探活脚本示例（解析 body 而非 HTTP 状态码）：

```bash
curl -s http://localhost:9200/nfy/api/v1/ops/health | grep -q '"status":"UP"' || echo "ALIVE 失败"
```

## 2. 外发引擎调优（`nfy.runtime.engine.*`，`NfyProperties.Engine` 核实）

| 配置键 | 出厂默认 | 作用 | 调优影响与风险 |
|---|---|---|---|
| `enabled` | `false`（app 出厂 yml 显式 `true`） | 引擎总开关 | 关掉则站外投递全部滞留 PENDING；嵌入形态默认关、需外发时显式开 |
| `scan-interval-ms` | `5000` | 扫描调度周期（`FOR UPDATE OF ... SKIP LOCKED` 领取） | 调小提升吞吐响应，但每轮都有 DB 领取查询，过小徒增空转压力 |
| `batch-size` | `50` | 单轮领取行数上限 | 待投积压大时优先调大它（比调小 scan 更有效）；过大则单轮持锁行数与内存占用上升 |
| `reaper-interval-ms` | `60000` | SENDING 超时回收检查周期 | 影响卡死行被重新捞起的时延，一般不动 |
| `sending-stale-ms` | `600000`（10min） | SENDING 判卡死阈值 | 调小于渠道外呼超时会**误回收造成重复投递**（at-least-once 契约可接受，但重复增多） |
| `backoff-seconds` | `"60,300,900"` | 失败退避阶梯（1/5/15min） | 渠道故障恢复快可缩短；重试上限固定 3 次（见 §3 DEAD 语义），阶梯长度超配无效果 |
| `rate-limit-per-minute` | `18` | 每渠道每分钟投递上限（0=不限） | 钉钉/企微群机器人硬限额 20/min，默认 18 留 10% 余量；自建 SMTP/邮件网关可放宽或 0。**单机内存固定窗**：多实例部署合计会超限（V1.0 已登记边界） |
| `worker-count` | `4` | 投递工作线程数（`nfy-engine-worker-N`，跨渠道**共享池**，V1.0 未按渠道隔离） | 慢渠道（外呼超时可达 5s+/邮件 30s）会占住 worker 拖累其他渠道，慢渠道多时调大 |

**何时调**：`scan`/`batch`——PENDING 积压持续增长且单轮领不满 50 行时；`backoff`——渠道侧故障恢复周期与 1/5/15min
阶梯明显不匹配时；`rate-limit`——换渠道供应商或收到平台方限频告警时。改参数即改 `nfy.runtime.engine.*` 配置并重启
（无运行时热更开关；线程与调度器在启动时创建）。

## 3. 投递观测与干预

### 3.1 投递查询（DLV-001，租户 token）

```
GET /nfy/api/v1/admin/deliveries
    ?biz_no=&userid=&channel_type=&status=&created_after=&created_before=&offset=&limit=
```

- 过滤项全部可选；`created_after/before` 为毫秒时间戳；分页 Offset 式，`limit` 缺省 20、上限 100（`DeliveryAdminService`）。
- 返回 `list[]`（`delivery_id/status/retry_count/next_retry_at/error_message/sent_at/target(已脱敏快照)` 等）+ `total`。
  注意口径：`total` 是**该租户投递行总数**，不随过滤条件收窄。
- 状态机（`DeliveryEngine`）：`PENDING →（领取）SENDING → SUCCESS | FAILED（按退避回到可扫）`；重试超限 → `DEAD`；
  SENDING 卡死由 reaper 回收为 PENDING（at-least-once，可能重复投递）。

### 3.2 人工重投（DLV-002）

```
POST /nfy/api/v1/admin/deliveries/{delivery_id}/retry     # 租户 token
```

- **仅 DEAD 可重投**：非 DEAD 返回 `10402`「非 DEAD 状态不可重投」；不存在/跨租户 `10400`。
- 效果：状态回 `PENDING`、`next_retry_at=now`（下一轮扫描即领取）。
- 注意：**`retry_count` 不清零**——若渠道故障未修复，重投后一次失败即再回 DEAD。先修渠道（或先重新验证渠道）再重投。

### 3.3 DEAD 语义

初次投递 + 3 次退避重试（1/5/15min）全部失败后进入 `DEAD` 终态（`MAX_RETRY=3`，第 4 次失败即 DEAD），
不再自动重试，**唯一出路是 DLV-002 人工重投**。平台域概览 `dead_count` 为全时段终态存量口径（跨日追溯，不设自然日窗口）。

### 3.4 渠道熔断的查看与恢复（无自动恢复）

- 触发：渠道**连续**失败 `fail_count ≥ 5` → 单语句 CAS 置 `DISABLED`（`DeliveryEngine#maybeBreakChannel`）；
  USER 渠道会向属主直插一条 `CHANNEL_ALERT` URGENT 站内信「渠道连续失败已自动停用」，TENANT 公共渠道仅日志。
- 查看：渠道列表接口（CHN-001，`status=DISABLED`）；引擎日志行 `[Engine] 渠道 {id} 连续失败 {n} 次，已熔断 DISABLED`。
- **恢复（人工路径，无自动恢复）**：
  - IM（钉钉/企微/飞书）：先 `POST .../verify` 重新验证成功（失败会以 `10604` 报摘要），再 patch `ENABLED`
    （未验证或仍处熔断态时 patch 直接 `10610`「渠道连续失败已自动停用，请重新验证」）。
  - EMAIL：`patch ENABLED` 即视为重新验证——熔断态（`fail_count≥5`）下同时把 `fail_count` 归 0（EMAIL 无主动验证通路，`verify` 恒 10604）。
  - 任一渠道**投递成功**也会清零 `fail_count` 并回填 `last_verify_at`（成功即可用性证据）。

## 4. 渠道运维

### 4.1 EMAIL / SMTP

| 配置键（`nfy.runtime.engine.*`） | 出厂默认 | 说明 |
|---|---|---|
| `mail-host` | 空 | **为空时 EMAIL 投递即失败**，error_message 逐字为 `SMTP 未配置(nfy.engine.mail-host)`，随后走重试→DEAD |
| `mail-port` | `25` | |
| `mail-from` | 空 | 为空时投递用兜底发件人 `notification4j@localhost`（多被收件方拒收，建议显式配置） |
| `mail-username` / `mail-password` | 空 | username 非空才启用 SMTP AUTH |

行为（`EmailSender`）：STARTTLS **恒开启**；连接超时 10s、读写超时 30s（防半开连接挂死 worker）。

**邮件全失败排查顺序**：① error_message 是否为 `SMTP 未配置(nfy.engine.mail-host)`（是→先配 host 重启）；
② `SMTP 失败:<异常类名>`（认证错/网关拒收/STARTTLS 不被支持）→ 核对端口（465/587 场景先确认网关支持 STARTTLS）与凭据；
③ 单渠道熔断见 §3.4。

### 4.2 verify 状态与 10604 处置

- EMAIL 的 `verify` **恒返回 `10604`**，文案逐字：`渠道验证失败:EMAIL 通道暂不支持主动验证(首次投递时校验)`——
  这是设计行为（校验发生在首次投递，成功后回填 `last_verify_at`），不是故障。
- IM 的 `verify` 失败同样落在 `10604`，message 携带摘要：`HTTP <状态码>` / `渠道无响应或连接失败` /
  渠道业务码失败 / keyword 前缀不符等——按摘要对照 [../03-开发指南/channels.md](../03-开发指南/channels.md) §2.4 修 webhook 配置后重试 verify。
- IM webhook 失效（群机器人被删/换 token）的注册、校验与脱敏细节：[../03-开发指南/channels.md](../03-开发指南/channels.md)。

## 5. 日志与追踪（trace_id 三贯通）

| 通道 | 形态 | 出处 |
|---|---|---|
| ① 响应信封 | 每个响应 body 携带 `trace_id` | framework4j-web `ApiResponse` |
| ② 响应头 | `X-Trace-Id`：请求可带（带则透传），**响应必返**，不传由服务端生成 | api-spec 公共头表 |
| ③ SQL 注释 | Druid Filter 注入 `/*traceid=xxx*/` 注释，PG 侧日志/慢 SQL 可直接按 trace_id 关联 | app yml `framework4j.sql-tracing.enabled=true, mode=ALL` |

- SQL 追踪三模式：`DISABLED` / `WRITE_ONLY` / `ALL`（app 出厂 `ALL`）。
- trace_id 落库面（varchar(68)，DDL 核实）：`nfyp_audit_log.trace_id` 存在，但 `nfya_delivery.trace_id` 计划期恒写空串（`DeliveryPlanService.java:164`）——**不能**按投递行反查 trace_id，链路追踪以日志/信封为准。
- **tracelog 控制台**：`http://<host>:9200/tracelog/index.html`——按 traceId/URL/userId 临时提权 DEBUG、日志聚合 Redis
  （静态页免鉴权，其 API `/api/logs/**` 需 OPS token）。容器化注意：Redis 故障降级目录
  `/tmp/notification4j-tracelog-fallback` 需挂载持久卷（app yml `tracelog.collection.fallback-dir`）。
- **日志位置**：出厂无文件 appender，全部走 **stdout**——由 systemd/journald、`docker logs`、K8s 日志体系收集。
- 引擎侧关键日志行（grep 友好）：`[Engine] 外发引擎已启动 scan=... batch=... reaper=...`、
  `[Engine] reaper 回收 SENDING 超时行 {n} 条`、`[Engine] 渠道 {id} 连续失败 {n} 次，已熔断 DISABLED`、
  `[Engine] 投递异常 delivery={id}`。

## 6. 监控口径（如实声明）

当前可观测面只有四块，均为「拉取式」，无指标暴露：

1. **health 端点**（§1，黑盒探活）；
2. **结构化日志**（§5，trace_id 贯通）；
3. **DB 投递表** `nfya_delivery`（状态/重试/错误快照，巡检 SQL 见 §7）；
4. **统计端点**：租户域 `GET /nfy/api/v1/admin/stats/overview`（`today_sent/today_delivered/deliver_success_rate/read_rate_7d/channel_count`）、
   平台域 `GET /nfy/platform/api/v1/stats/overview`（`tenant_count/active_tenants/today_messages/today_deliveries/deliver_success_rate/dead_count/dead_tenants[]`）。

**指标埋点未实装**：backend 全部 pom 零 micrometer 依赖，无 `/actuator/metrics`、无 Prometheus 暴露面；
SAD §7.4 的 Metrics/告警设计为**规划口径**，当前版本请以 health + 日志 + DB 巡检组合运维。

基于现有面可落地的最小告警建议（阈值按业务自定）：

| 信号 | 采集方式 | 指向 |
|---|---|---|
| health 非 UP | 周期探测 §1 端点 | PG/Redis 断连或池耗尽 |
| PENDING 积压持续增长 | §7 巡检 SQL 第一条 | 引擎停摆（`enabled` 被关 / worker 不够 / 渠道全 DISABLED） |
| DEAD 存量突增 | 巡检 SQL / 平台概览 `dead_count` | 渠道故障（对照熔断日志行） |
| `10500`/`429` 集中出现 | 网关/反代日志按信封 code 统计 | 限流阈值或防爆破锁（误配凭据的接入方） |

## 7. 容量与清理

- **retentionDays 未实装（如实声明）**：租户 `config` jsonb 可经平台域 PTE-002 写入 `config.retentionDays`
  （`PatchTenantRequest` 定义该字段组），但后端**没有任何清理任务消费它**（starter 无 @Scheduled 清理 Job，投递引擎
  自带的调度只做扫描/reaper）。当前它是「仅存储」的预留配置——历史数据保留策略需 DBA 自管（按 `created_at` 归档/删除）。
- 增长最快的表：`nfya_message` / `nfya_message_recipient`（每收件人一行）/ `nfya_delivery`（每站外投递一行）/
  `nfya_announcement_read` / `nfyp_audit_log`（append-only 表 + hash 唯一索引就绪；**注意**：v1.4.0 starter 无任何 `@Auditable` 注解点，该表当前不产生行——启用需业务侧自行标注）
  `uk_nfyp_audit_log_hash`——清理/归档时保持 append-only 语义，勿 UPDATE 历史行）。
- **部分索引护航引擎扫描**（V1.0.0 DDL）：`idx_nfya_delivery_pending ON (next_retry_at) WHERE status IN ('PENDING','FAILED')`、
  `idx_nfya_delivery_sending ON (updated_at) WHERE status='SENDING'`——索引只覆盖活跃行，**历史行增长不放大引擎扫描成本**；
  用户查询面有 `idx_nfya_recipient_unread WHERE read_status='UNREAD' AND is_deleted=0`、
  `idx_nfya_delivery_tenant_time (tenant_id, created_at DESC, id DESC)` 等时间序索引。
- DB 巡检参考 SQL（对已核实 schema 的通用查询）：

  ```sql
  SELECT status, count(*) FROM nfya_delivery GROUP BY status;   -- PENDING 积压 / FAILED 重试 / DEAD 存量
  SELECT channel_type, count(*) FROM nfya_delivery
    WHERE status = 'DEAD' GROUP BY channel_type;                -- DEAD 按渠道分布（配合 §3.4 熔断排查）
  ```

- **dead_tenants 概览**：平台域 `GET /nfy/platform/api/v1/stats/overview` 返回 `dead_count`（全时段 DEAD 终态存量）
  与 `dead_tenants`（DEAD 最多的租户 Top ≤20，`{open_id, dead_count}`）——运维发现某租户持续上榜时，
  用该租户 token 走 DLV-001/002 定位与重投。

## 8. 常见运维故障速查

| 现象 | 首查 | 处置 |
|---|---|---|
| 启动即退 | 日志首屏 | 机密 env 缺失 fail-fast：`JWT_SECRET`/`AES_KEY`/`PLATFORM_CLIENT_SECRET` 无默认值（环境变量表见 [deployment.md](deployment.md) §3.1） |
| 启动报端口占用 | `server.port: 9200` | `--server.port=` 换端口或释放占用 |
| 启动报数据源/迁移失败 | 先看 Flyway 连接（`spring.flyway.url`） | PG 不可达/凭据错；**两处 url 都要覆盖**（flyway + `framework4j.datasource...`）；库非空会被 `baseline-on-migrate=false` 拒绝 |
| EMAIL 投递全失败 | DLV-001 查 `error_message` | 逐字 `SMTP 未配置(nfy.engine.mail-host)` → 配 `mail-host` 等五键并重启（§4.1） |
| 引擎不扫描 | 日志有无 `[Engine] 外发引擎已启动` | 无此行=引擎未开（`nfy.runtime.engine.enabled`，嵌入形态默认 false）→ 显式 true |
| FAILED 行迟迟不重试 | `next_retry_at` 与退避阶梯 | 未到点（1/5/15min）属正常；渠道 DISABLED 时其投递行**不会被领取**（claimBatch JOIN 仅取 ENABLED 渠道，行留原地不计重试，`NfyaDeliveryMapper` javadoc 口径）——恢复渠道（verify/patch 重启用）后自动续投 |
| SENDING 长期不动 | reaper 日志行 | worker 卡在慢外呼；`sending-stale-ms`（出厂 10min）后 reaper 自动回收；频繁出现考虑调 `worker-count` |
| 换 token 返回 429 | 防爆破锁 | 连续错 5 次锁 15min（维度=client_id，成功即清零）——核对 client_id/secret，等待或换正确凭据 |
| runtime 请求 `10500` + Retry-After | 限流 | 出厂 100 次/分钟/客户端 IP/路径（滑窗；`default-scope: APP` 大写未命中 fwk4j 小写分支，实际按 IP 维，NAT 共桶——yml:160 注释「按 tenant_id」为陈旧口径）——按 Retry-After 退避或申请调 `framework4j.rate-limit.default-limit` |
