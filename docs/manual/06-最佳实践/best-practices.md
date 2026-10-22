# 最佳实践：可靠发送、安全加固、容量与多环境治理

> notification4j 使用手册 · 06-最佳实践第 1 篇 · 版本口径 **v1.4.0**（Maven `1.4.0` / tag `v1.4.0`，底座 framework4j v1.7.1）。
> 本文每条建议均落在已核实的源码 / 行为上，键名与错误码与手册各篇口径一致。取证源：03 全篇
> （sending / signature / auth / channels / announcements / onboarding / platform-admin / frontend-embed）、
> 05 全篇（deployment / configuration / operations）、[error-codes.md](../04-参考/error-codes.md)、
> [api-spec §2/§6](../../api/api-spec.md)、app `application.yml`、starter 源码（`BatchJobService` /
> `AuthenticatedHttpTransport` / `NfyaChannel` / `RemoteNotifyClient`）。上线自查见同目录
> [security-checklist.md](security-checklist.md)。

## 1. 可靠发送

### 1.1 幂等双闸：biz_no 与 Idempotency-Key 的分工

系统有两层防重复闸，覆盖面与寿命不同，**不是替代关系**：

| 闸 | 实现与覆盖面 | 冲突表现 | 寿命 | 正确用法 |
|---|---|---|---|---|
| `biz_no` | DB 唯一约束 `(tenant_id, biz_no)`（真闸）；覆盖 MSG-001/002 发送与公告创建（后者文案「业务号重复，公告已存在」） | `10401`（HTTP 200 信封） | **永久**（撤回后同号仍 10401） | 业务幂等键：跨重试/重放/对账永久去重，且可凭它查 MSG-003 发送结果 |
| `Idempotency-Key` | fwk4j 幂等拦截（Redis 48h，`ttl-seconds: 172800`）；仅覆盖 **3 条精确路径** `/nfy/api/v1/runtime/messages`、`.../channels`、`.../subscriptions`（app yml `idempotency.path-patterns`，Ant 精确匹配——`/messages/batch`、`/messages/{id}/cancel` 等子路径**不在面内**） | 同 key 异 body → **HTTP 409 + 10501**；同 key 同 body → 回放首次响应 | 48h | 传输层防抖：网络超时重发时拿回首次结果，不产生第二个副作用 |

- **biz_no 设计**：`{业务域}-{事件}-{唯一号}`（如 `order-pay-{orderId}`），≤68 字符；不关心幂等可缺省（服务端补 UUID），但**拿不到幂等保护**——重试风暴下会产生重复消息。
- **批量端点只有 biz_no 半闸**：MSG-002 不在 Idempotency-Key 覆盖面内，同 body 重复提交生成两个 job；显式 biz_no 重提时首批撞 `10401` 计入 `failed_items`（PARTIAL）。**批量重提务必换 biz_no**。
- 撤回（MSG-009）幂等免 Header；同 biz_no 重发仍 `10401`——发错内容的姿势是「撤回 + 换新号重发」。

### 1.2 批量拆分：1000 / 100000 两条边界

| 边界 | 端点 | 行为 |
|---|---|---|
| 去重后 ≤1000 | MSG-001（同步） | 超限 `10102`「接收人数量超限(单次≤1000)」；一次请求一条 message、落库即回 message_id |
| 去重后 ≤100000 | MSG-002（异步） | 超限 `10102`「批量发送人数超限(≤100000)」；立即返回 job_id，结果靠 JOB-001 轮询 |

内部拆批口径（`BatchJobService`，对调用方可见的副作用）：

- **每 1000 人一条 message、逐批独立事务**——失败批不影响已成功批（终态 DONE / PARTIAL / FAILED）；
- 显式 `biz_no` 首批复用原值、后续批自动加 `-bN` 后缀；未传则每批独立 UUID。因此 **>1000 人的批量产生多条
  message_id，撤回需逐 message_id 调 MSG-009**；
- job 状态存 Redis **TTL 48h**，过期/不存在/跨租户一律 `10400`；`failed_items` 截断 ≤100。轮询以 2s 量级
  间隔拉到终态为止，无推送无回调。

实践：大规模推送按业务段落**分段提交多个 job**（控失败爆炸半径，失败批可独立重提新号）；勿在调用方循环发
千次 MSG-001 代替批量——每条都是独立事务与独立幂等键，失败面更大（sending.md §11 同口径）。

### 1.3 URGENT 使用门槛：全渠道恒投的代价

`level=URGENT` 在 `DeliveryPlanService.resolveTargets` 中被特判（`"URGENT".equals(level)`）：投递计划展开为
该用户**全部 ENABLED 注册渠道**、**不经订阅矩阵**、**豁免免打扰**（恒即时）。代价与门槛：

- 用户**无法**通过订阅矩阵关闭 URGENT——打扰不可拒，只用于安全 / 资损 / 法定级事件；
- 全渠道恒投放大外发消耗（EMAIL/IM 侧配额与费用），且批量 URGENT 会瞬间占满引擎限速（18/min/渠道）；
- `IMPORTANT` 与 `NORMAL` 走同一条矩阵展开路径，仅展示口径不同——常规分级用这两档；
- 缺省不保底：消息未传 level 一律 `NORMAL`（**不取类型 default_level**）；公告 level 缺省是 `IMPORTANT`（两端不同）；
- 如实声明：宿主侧 `NFY_URGENT` 上升沿通知因后端 `unread-count` 响应无 `has_urgent` 字段，**当前实际不发出**。

### 1.4 INAPP 先行与降级思路

站内信是**同步语义**：send 同一事务内写 recipient 行（UNREAD），`inapp_saved=true` 即入库可见可查
（`GET .../unread-count` / MSG-004 / MSG-008 立即可用）；INAPP 哨兵不产投递行，`delivery_planned` 只统计站外。

- **保底姿势**：消息类型 `default_channels` 保留 `INAPP`（出厂缺省即 `["INAPP"]`）——即便用户无任何外发渠道，
  站内可见性不丢；
- **降级隔离**：站外渠道故障 / 熔断（连续失败 ≥5 → DISABLED）只影响 `nfya_delivery` 行（重试 60/300/900s →
  DEAD），**不回滚站内落库**；投递结果是纯拉取（MSG-003 send-results / DLV-001），无回调，勿等推送；
- **先决条件**：EMAIL 依赖 SMTP——`nfy.runtime.engine.mail-host` 未配置时 EMAIL 投递逐字报
  `SMTP 未配置(nfy.engine.mail-host)` 走重试 → DEAD；先配引擎五键再启用 EMAIL 渠道；
- 嵌入形态 `engine.enabled=false`（缺省）时站外全部滞留 PENDING，站内面照常——宿主自带调度或显式开启；
- 已知缺口：remote 模式 `NotifyClient.unreadCount/listMessages` **不注 `X-User-Id` 头**（`RemoteNotifyClient`
  丢弃 userid 参数），T+U 端点必回 `10101`——按用户查询请自定义 `HttpTransport` 注头或走 SPA 嵌入面。

## 2. 安全

### 2.1 签名开启决策：只有纯 S2S 才需要

| 形态 | 决策 | 依据 |
|---|---|---|
| 嵌入 / 混合（浏览器 SPA 直连 runtime） | **不开** | SPA 拿不到租户 secret，强制签名 = 消息中心全拒 `10101`（D-1 出厂决策） |
| client-starter 跨进程，一般业务 | 可不开 | Bearer 已鉴权、TLS 已加密；签名增量收益 = 防重放 / 防报文篡改 |
| 纯 S2S 独立部署 + 高安全诉求 | **开** | 四层配齐见下 |

开启步骤（缺一层签名面不生效，[signature.md](../03-开发指南/signature.md) §2）：
① 服务端 yml 加回 `framework4j.signature.path-patterns`（app yml 注释含示例 `["/nfy/api/v1/runtime/**"]`）；
② 平台 `PATCH /nfy/platform/api/v1/tenants/{open_id}` 置 `privileges.signature=true`；
③ 租户 SEC-001 注册 16~71 位密钥并安全下发给调用方；
④ 调用方配齐 `nfy.runtime.remote-tenant-id` + `remote-tenant-secret` 两键（client-starter 自动注入四头+Bearer）。

**验证**：不带四头的请求应得 HTTP 401 + 信封 `10101`「签名头缺失」；带正确四头 code=0。
**回滚**：patterns 清回 `[]` 并重启——fwk4j patterns 为空即不注册拦截器，调用方残留的四个签名头被忽略、无副作用；
`privileges.signature` 可不回滚（当前仅存储，不参与拦截）。

### 2.2 密钥轮换顺序：签名侧无宽限

`tenant_secret` 一钥双用途（认证凭据 + HMAC 密钥），但两侧对旧密钥的宽容度不同：**认证侧换 token 有 24h 宽限**
（`framework4j.tenant.secret.grace-hours: 24`，旧值入 `tenant_secret_prev`），**签名侧立即失效**
（`NfyTenantSecretProvider` 只返回当前密钥 → 旧密钥签名即刻 `10200`）。因此顺序有讲究：

1. 生成 16~71 位新密钥，安全通道下发给调用方；
2. 调用方切换 `remote-tenant-secret`（配置重启）；
3. 验证：新密钥换 token 成功 + 带签名请求 code=0；
4. 最后才在 SEC-001（或平台 PTE-003）登记轮换——响应 `rotated:true`，新密钥**立即生效**；
5. 24h 宽限窗口内旧密钥仍可换 token（认证面），观察无误后自然失效，无需清理动作。

错误顺序（先登记后切换调用方）→ 未切换的签名调用方立即 `10200` 全拒。另注意：重置密钥**不撤销**在途
access token；要立即断流配合 SUSPEND（§4.3）。

### 2.3 secret 最小暴露面

- 明文只出现两次：创建（PTE-001/OPEN-001）与重置（PTE-003）响应；库内 `tenant_secret` /
  `tenant_secret_prev` / 渠道加签 `secret` 均为 AES-GCM 加密列（`LazyEncryptedFieldTypeHandler`）。
- 独立部署机密走环境变量注入且 fail-fast：`JWT_SECRET`（≥32 字符）/ `AES_KEY`（≥32）/ `PLATFORM_CLIENT_SECRET`
  缺一启动即败；`HASH_SALT` 缺省 `salt`，生产显式覆盖。密钥不入 git、不进镜像层。
- client-starter 侧：`remote-tenant-secret` 同样走 env/secret 管理；**缺任一键时签名四头与 Bearer 一并不注入、
  仅打 WARN**（`AuthenticatedHttpTransport.enrich` 两键检查先于 Bearer 分支）——首个请求将 `10200`，上线前
  冒烟一次即可暴露。
- 前端面：`client_secret` 永不出宿主后端，token 仅经 postMessage 进 iframe 内存、不落 URL/storage
  （frontend-embed.md §3/§8）。

### 2.4 token 生命周期管理（8h + 重握手）

- 出厂 8h 绝对上限（`expire-time: 28800`，配置上限 12h）+ 滑动续期（`auto-renew: true` +
  `renew-increment: 1800`：每次校验通过把会话 TTL 续到 30min）——**持续使用最长 8h、静置 30min 即过期**；
  M2M 无 refresh_token，续期 = 到期前 5min 用密钥重换；每次通过校验的响应带 `X-Token-Expire-At`（剩余秒数）。
- **单会话模型**：同一租户再换一次 token，旧 token 即失效（`10205`「账号已在别处登录」）——双实例共用同一
  凭据会互踢，**每个接入方独立租户**（或错峰重换）。
- 换 token 失败不要密集重试：同 client_id 连错 5 次锁 15min（信封 `429`，正确凭据也被拒；成功一次即清零）。
- 嵌入面：token 失效触发 iframe `expired` 回 waiting 并重发 `NFY_READY`，宿主每次 READY 都重新代换回投即自愈。

## 3. 性能与容量

### 3.1 限流真相与容量预估

出厂实况（app yml + fwk4j `RateLimitKeyResolver`）：**100 次/分钟 / 客户端 IP / 路径**（Lua 滑窗，
key=`notification4j:rl:APP:{ip}:{path}`；`default-scope: APP` 大写未命中 fwk4j 小写 `ip/user/app/global` 分支落
default → 按 IP 维）。超限 HTTP 429 + `10500` + `Retry-After`。

- **NAT 共桶**：出口 IP 集中的调用方（公司统一出口、网关侧 Pod 共 IP）共享同一 100/min 桶——与租户数无关。
- 量级估算（算术，非实测基线）：发送面 `/runtime/messages` 100 次/min × ≤1000 人/次 = 理论 10 万接收人/min；
  嵌入面铃铛 30s 轮询 = 每用户 2 次/min，同出口约 50 个活跃用户即触顶 `/unread-count` 桶。
- **出口 IP 集中的调用方怎么办**：① 调 `framework4j.rate-limit.default-limit` 提额（全局面，重启生效）；
  ② fwk4j 加 scope 维度——纯 S2S 部署可改小写 `app`（按 `X-Access-Key` 头分桶；注意混合形态下 SPA 不带该头
  会全落 `unknown` 共享桶，勿在嵌入/混合形态使用）；③ 高频查询面收敛轮询间隔或走宿主聚合。
- auth 端点在 `whitelist-paths` 白名单内（无调用量闸，仅防爆破锁兜底）；admin 面不在 path-patterns 内。

### 3.2 投递 / 回执表增长与索引现状

12 张表（V1.0.0 DDL）中增长最快：`nfya_message` / `nfya_message_recipient`（**每收件人一行**）/
`nfya_delivery`（**每站外投递一行**）/ `nfya_announcement_read` / `nfyp_audit_log`（append-only）。

- 引擎扫描由**部分索引**护航：`idx_nfya_delivery_pending ON (next_retry_at) WHERE status IN ('PENDING','FAILED')`、
  `idx_nfya_delivery_sending ON (updated_at) WHERE status='SENDING'`——索引只覆盖活跃行，**历史行增长不放大
  引擎扫描成本**；用户查询面另有 `idx_nfya_recipient_unread`（部分索引）与 `idx_nfya_delivery_tenant_time`。
- **retentionDays 仅存储、无清理任务**（如实声明）：租户 `config.retentionDays` 无任何 @Scheduled 消费方，
  历史数据保留策略需 DBA 按 `created_at` 自管归档/删除；`nfyp_audit_log` 归档时保持 append-only，勿 UPDATE 历史行。

### 3.3 引擎参数调优原则

参数语义与风险全表见 [operations.md](../05-部署与运维/operations.md) §2（`nfy.runtime.engine.*`）。调优顺序：

1. **PENDING 积压**：优先调大 `batch-size`（比调小 `scan-interval-ms` 有效且省空转）；改参数须重启（无热更）；
2. **重试节奏**：`backoff-seconds`（出厂 `"60,300,900"`，重试上限固定 3 次超限 DEAD，阶梯超配无效果）只在
   渠道故障恢复周期与 1/5/15min 明显不匹配时动；
3. **限速**：`rate-limit-per-minute` 出厂 18（钉钉/企微群机器人硬限额 20/min 留余量；自建 SMTP 可放宽，
   **0 = 不限速**）；注意是**单机内存固定窗**，多实例部署合计会超限（V1.0 已登记边界）;
4. **慢渠道拖累**：`worker-count`（出厂 4，跨渠道共享池）在邮件（读写 30s）等慢渠道多时调大；
   `sending-stale-ms`（出厂 10min）勿调小于渠道外呼超时——否则 reaper 误回收造成重复投递增多。

## 4. 多环境与租户治理

### 4.1 测试 / 生产租户隔离

- 数据隔离靠 `tenant_id` + 防探测（跨租户访问统一 `10400` 同码同文案），配置档 `privileges/config/oem`
  按租户独立；但**平台公告（tenant 0）publish 后全部租户用户可见**、平台统计/PPM 是跨租户面——同实例上
  「测试租户」无法隔离平台域操作的影响面。
- 建议：**测试与生产各自部署实例**（fat jar 一个进程一套数据面，隔离成本最低）；退而求其次同实例分租户，
  且平台域操作（PAN/PTE）仅在生产由专人执行。注册码 `preset` 可预绑 privileges，测试开通走注册码留痕。

### 4.2 profile 切换注意：dev 段承载了全部

app 出厂 `application.yml` 是两段式：首段无条件生效（server/flyway），**`framework4j.*` 与 `nfy.*` 全部位于
`spring.config.activate.on-profile: dev` 段内**，且 `spring.profiles.active: dev` 写在首段（裸 `java -jar` 即
「出厂等价实例」）。切 `--spring.profiles.active=prod` 时 **dev 段整体失效**，必须自带等价配置
（外置 `application-prod.yml` / `spring.config.import` / 环境变量 / 命令行）——不补配时
`framework4j.datasource.enabled` / `redis.enabled` 缺省 false，进程无业务 DataSource/Redis（静默空壳）。

### 4.3 SUSPEND / CLOSE 治理语义

| action | 前置 | 效果（PTE-004，源码 `PlatformTenantService.changeStatus`） |
|---|---|---|
| SUSPEND | 仅 ACTIVE | **三面同断**：换 token 信封 401（与凭据错同码防探测）；在途 token `10201`（撤销全部存量会话）；签名面 `10200`（非 ACTIVE 租户不出签名密钥） |
| RESUME | 仅 SUSPENDED | 恢复 ACTIVE，**原密钥可继续换 token**（无需重置） |
| CLOSE | 非 CLOSED | 终态；前置该租户无 PENDING/SENDING 投递任务，否则 `10402`「存在未完成投递任务…暂不可注销」 |

治理用法：疑似凭据泄漏的**立即断流**动作是 SUSPEND（重置密钥有 24h 宽限且不撤在途会话）；定期演练
SUSPEND → 观察 `10201`/401 → RESUME 全链，确保应急路径可用。

## 5. 反模式清单（每条均有源码 / 实测支撑）

| # | 反模式做法 | 后果 | 正确姿势 |
|---|---|---|---|
| 1 | 循环发千次 MSG-001 代替批量 | 每条独立事务+独立幂等键，失败面大、耗时限流桶 | ≤1000 用 MSG-001；更大走 MSG-002 轮询终态 |
| 2 | 把 URGENT 当常规等级 | 绕订阅矩阵+免打扰豁免，用户不可关，全渠道消耗 | 常规分级 NORMAL/IMPORTANT（同路径仅展示差异） |
| 3 | 撤回后复用原 biz_no 重发 | `10401`（撤回不触碰幂等记录） | 撤回 + **换新号**重发 |
| 4 | 批量提交重试不换 biz_no | 不在幂等面：重复生成 job；显式号首批 `10401` 计入 failed_items | 批量重提换号；分段提交控爆炸半径 |
| 5 | 双实例共用同一租户凭据换 token | 单会话模型互踢 `10205` | 每个接入方/实例独立租户 |
| 6 | 密钥轮换先登记后切换调用方 | 签名侧无宽限，旧密钥即刻 `10200` 全拒 | 先下发切换验证，最后登记（§2.2） |
| 7 | 渠道未修复就重投 DEAD 行 | `retry_count` 不清零，一次失败即再回 DEAD | 先修渠道/重新 verify，再 DLV-002 重投 |
| 8 | 切生产 profile 不带等价配置 | fwk4j/nfy 键全在 dev 段，数据面闸不开成空壳 | 外置配置自带等价键（§4.2） |
| 9 | 告警只盯 HTTP 状态码 | auth 端点 401/429 是**信封码且 HTTP 200**，漏报爆破 | 以信封 code 分支，HTTP 码仅分层 |
| 10 | 宿主 postMessage 用 `targetOrigin:'*'` / 不验 `e.origin` | token 可被任意嵌页方截获 | 双向显式 origin + 白名单 fail-closed |
| 11 | 以为 `privileges.signature=true` 就已开签名 | 该键仅存储，拦截由部署侧 path-patterns 决定 | 三层配齐（yml pattern + privileges + SEC-001） |
| 12 | 把 IP 限流当租户配额做容量规划 | 实际按客户端 IP 分桶，NAT 共桶、多租户同 IP 共享 | 按 §3.1 估容量，出口集中方提额/换 scope |

## 下一步

- 上线前安全自查：[security-checklist.md](security-checklist.md)（同目录）
- 故障排查速查（06 篇后续批次）：[troubleshooting.md](troubleshooting.md) · 错误码字典：[error-codes.md](../04-参考/error-codes.md)
- 引擎与投递运维细节：[operations.md](../05-部署与运维/operations.md) · 升级口径：[upgrade-guide.md](../07-版本与支撑/upgrade-guide.md)
