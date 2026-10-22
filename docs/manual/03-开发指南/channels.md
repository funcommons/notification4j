# 开发指南：渠道接入（IM 群机器人 / 邮箱）

> 适用版本 **v1.4.0**。讲清楚「通知怎么送出站内」：终端用户/租户如何注册钉钉、企微、飞书群 webhook
> 与邮箱渠道，如何验证、启停、熔断恢复。渠道现状：`INAPP` 站内信（恒有，不可注册不可关）、
> `EMAIL`、IM webhook（`DINGTALK`/`WECOM`/`FEISHU`）已实现；`SMS` 为规划渠道（§6 一句话口径）。
> 文中端点 / 错误码 / 阈值逐一取自仓内源码（`ChannelCoreService`、`WebhookTargetValidator`、
> `ChannelVerifier`、`DeliveryEngine`、`EmailSender`、`NfyProperties`），契约全文以
> [api-spec §5.7/§5.9](../../api/api-spec.md) 为准。

## 1. 渠道模型总览：谁拥有渠道

| 维度 | 用户自注册渠道 | 租户公共渠道 | INAPP 站内信 |
|---|---|---|---|
| 编号 | CHN-001~005（runtime 面） | ACH-001~003（admin 面） | 恒有，不可注册 |
| scope | `USER` | `TENANT`（`userid=''`） | 哨兵，非渠道行 |
| 端点前缀 | `/nfy/api/v1/runtime/channels` | `/nfy/api/v1/admin/channels` | — |
| 鉴权 | TENANT token + `X-User-Id` | 仅 TENANT token（body 无 userid） | — |
| 同类型上限 | 每用户每类型 ≤5（第 6 个 `10605`） | 不设上限 | — |
| 用途 | 定向消息按订阅矩阵外发 | 公告广播外发（`channel_ids` 引用） | 落库即达，不走投递 |

- 两面共用同一注册核心（`ChannelCoreService` 以 `scope` 参数收口），行为差异仅三处：
  userid 维度、≤5 上限（仅 USER）、owner 校验 `10400` 文案（USER 面「资源不存在或无权访问」/
  TENANT 面「渠道不存在」——防探测同码，跨租户/跨用户/已删同一码）。
- **scope 隔离**：公共渠道只出现在 admin 列表，不进任何用户的「我的渠道」列表，也不进订阅矩阵
  `available_channels`（该段只列用户本人 `scope=USER` 且 `ENABLED` 的渠道 + INAPP 哨兵）；
  公共渠道只被公告 `channel_ids` 引用（外发展开时按 `ENABLED` 过滤，死引用无害）。

## 2. IM 群 webhook 注册（CHN-002）与 SSRF 校验

### 2.1 注册请求

`POST /nfy/api/v1/runtime/channels`（TENANT token + `X-User-Id`）：

| 字段 | 必填 | 校验（`PostChannelsRequest` + 业务校验） | 说明 |
|---|---|---|---|
| channel_type | 是 | 枚举 DINGTALK/WECOM/FEISHU/EMAIL，非法 `10100` | |
| name | 是 | 1~30 字符 | 如「运维报警群」 |
| target | 是 | ≤516；IM=webhook URL（下节校验）；EMAIL=邮箱地址 | |
| secret | 否 | ≤128 | 加签密钥（钉钉/飞书建议配） |
| keyword | 否 | ≤20 | 机器人「自定义关键词」安全设置 |

注册**只落库 PENDING、不发验证消息**（P99 ≤ 300ms，验证是独立接口）；
成功响应 `data = {channel_id, status:"PENDING", verify_tip:"请调用 verify 接口发送验证消息"}`。
同 target 重复由唯一索引 `uk_nfya_channel_target (tenant_id, userid, channel_type, md5(target))`
兜底 → `10401「该渠道已存在」`。EMAIL 的 target 落库前做 trim + 小写归一化
（防 `A@x.com`/`a@x.com` 变体绕过 md5 唯一闸）。

### 2.2 webhook_url 校验全解（`WebhookTargetValidator`，SSRF 防线）

IM 类 target 必须同时满足，任一违规即 `10609「Webhook 地址非法或非官方域名」`：

1. **scheme 必须 https**（`http://` 明文直接拒）；
2. **官方域名白名单**（源码逐字）：精确匹配 `oapi.dingtalk.com`、`qyapi.weixin.qq.com`、
   `open.feishu.cn`，加后缀通配 `*.feishu.cn`（即 host 以 `.feishu.cn` 结尾均放行）；
3. **端口仅 443**（URL 不写端口或缺省 443 均可，写 `:8443` 等其他端口拒）；
4. **DNS 解析禁内网段**：对 host 做真实 DNS 解析（`WebhookTargetResolver`），**逐 IP** 校验，
   解析失败同判 `10609`。禁用地址集：环回 / 站点本地（10.x、172.16-31.x、192.168.x）/
   链路本地 / 任意地址 / 组播 + IPv4-mapped IPv6 展开复检 + CGNAT `100.64.0.0/10` +
   ULA `fc00::/7`。
   > 部署注意：出口 DNS 若走代理 fake-IP（如 `198.18.0.0/15` 或 ULA 回答）会命中禁用集，
   > 白名单域名注册同样被拒 10609——生产要求直连 DNS 解析到公网地址（E2E 环境发现项 E-L4-01）。

EMAIL 走另一条路：正则 `^[\w.+-]+@[\w-]+(\.[\w-]+)+$` + 长度 ≤254，违规 `10100「邮箱地址格式非法」`
（零 DB 判定归 101xx），**不做 DNS 解析**。

### 2.3 注册/列表错误码速查

| code | 场景 | message（源码原文） |
|---|---|---|
| 10609 | webhook 非 https / 非白名单域 / 非 443 / DNS 解析内网或失败 | Webhook 地址非法或非官方域名 |
| 10100 | channel_type 枚举非法 / target 空 / 邮箱格式非法 / name 超 30 | channel_type 枚举非法(允许 DINGTALK/WECOM/FEISHU/EMAIL) 等 |
| 10605 | 同用户同类型已有 5 个（仅 USER 面） | 同类型渠道数量超限(≤5) |
| 10401 | 同 target 重复注册（唯一索引真闸） | 该渠道已存在 |

> 说明：早期文档出现过的 `10602` 已归位为 `10102`（零 DB 判定的数量超限统一 101xx），渠道域不再使用 10602。

### 2.4 验证语义（CHN-003，`ChannelVerifier`）

`POST /nfy/api/v1/runtime/channels/{channel_id}/verify`（天然幂等，免 Idempotency-Key）。
同步外呼**最长 5s**（connect/read 各 5000ms），成功判定 = **HTTP 2xx 且渠道业务码成功**：
钉钉/企微 `errcode=0`、飞书 `code=0`——业务失败（关键词不匹配/加签错误/机器人被移出群）时
HTTP 仍是 200，只看状态码会把坏渠道误置 ENABLED。失败 → `10604「渠道验证失败:{渠道返回摘要}」`
（摘要取响应体前 120 字符；HTTP 4xx/5xx 摘要只有状态码，连接失败为固定文案，不回传目标地址防泄漏）。

验证消息文案 = `keyword + 【notification4j】渠道验证消息`（关键词机器人必含关键词才能通过）。
加签口径：钉钉 secret 以 **query** 携带 `timestamp`（毫秒）+ `sign`；飞书以 **body** 携带
`timestamp`（秒）+ `sign`；企微 webhook key 即鉴权、无加签。三口径与投递时完全一致（`ImWebhookSender`）。

成功 → `ENABLED` + `fail_count` 清零 + `last_verify_at` 置位，响应
`data = {channel_id, status:"ENABLED", last_verify_at:<epoch millis>}`。

### 2.5 列表（CHN-001）与脱敏

`GET /nfy/api/v1/runtime/channels` → `data.list[]`：
`{channel_id, channel_type, name, target(脱敏), status, fail_count, last_verify_at}`。
target 脱敏规则（`ChannelService.mask`，投递快照落库同款）：EMAIL `a***@domain`；
FEISHU path 末段凭证遮蔽（`.../hook/****`）；其余 IM 保留 scheme+host、查询串全遮蔽（`...?****`）。

## 3. EMAIL 渠道：注册、验证语义与 SMTP 配置

- **注册**：同 §2.1（channel_type=EMAIL，target=邮箱地址，仅格式校验不做 DNS）。
- **verify 恒 `10604`**：`渠道验证失败:EMAIL 通道暂不支持主动验证(首次投递时校验)`——EMAIL 没有
  主动验证通路，可用性由首次真实投递校验（v1.2.2 前 verify 10604 与 patch 10610 曾构成死锁，
  纯 API 永远无法 ENABLED）。
- **v1.2.2 修复后 patch 语义（ND-L5-01）**：`PATCH /{channel_id}` 对 EMAIL 豁免「已验证」前置——
  ① PENDING 可直接 `status:"ENABLED"`（首次投递时校验兜底）；② 熔断态显式重新启用 = 重新验证，
  置 ENABLED 同时 `fail_count` 归 0。**IM 行为一字不变**：未验证或熔断态启用仍 `10610`。
- **引擎投递成功回置**：投递 SUCCESS 时 `DeliveryEngine` 清 `fail_count` **并回写 `last_verify_at`**——
  投递成功即可用性证据，EMAIL 的「已验证时点」由此取得。
- **SMTP 出厂配置键**（`NfyProperties.Engine`，前缀 `nfy.runtime.engine.`）：

| 键 | 缺省 | 说明 |
|---|---|---|
| `nfy.runtime.engine.mail-host` | `""`（空） | **为空时 EMAIL 渠道直接投递失败**（报 `SMTP 未配置(nfy.engine.mail-host)`，走重试→DEAD），须显式配置 |
| `nfy.runtime.engine.mail-port` | `25` | SMTP 端口 |
| `nfy.runtime.engine.mail-from` | `""` | 发件人；空时兜底 `notification4j@localhost` |
| `nfy.runtime.engine.mail-username` / `mail-password` | `""` | 账号密码（username 非空才开 SMTP AUTH） |

发送实现（`EmailSender`）：Jakarta Mail，强制 STARTTLS，连接超时 10s / 读写超时 30s，无异常即成功。

## 4. 租户公共渠道（ACH-001~003，admin 面）

`/nfy/api/v1/admin/channels`：`GET` 列表（同款脱敏）/ `POST` 注册（body 无 userid）/`{channel_id}`
`PATCH` 改名启停 / `DELETE` 逻辑删 / `{channel_id}/verify` 验证——**与用户渠道同构**
（注册校验、验证语义、10609/10401/10604/10610 全部一致），差异仅：

- scope=TENANT、`userid=''`，无 `X-User-Id`；
- **无同类型 ≤5 上限**（租户公共资源）；
- 删除不做订阅级联（公告 `channel_ids` 中的死引用在外发展开时按 `ENABLED` 过滤兜底）；
- 熔断只打日志、不发属主站内信（公共渠道无属主）。

用法：创建并验证 ENABLED 后，在公告 `channel_ids` 中引用即随发布外发（详见
[announcements.md](announcements.md)）；公共渠道不进用户订阅矩阵（scope 隔离见 §1）。

## 5. 渠道状态机与熔断恢复

状态常量（源码）：`PENDING` → `ENABLED` → `DISABLED`（仅此三态；`status` 枚举外取值 `10100`）。

```
注册 ──► PENDING ──┬── verify 成功 ──────────────► ENABLED ──┬── patch{status:"DISABLED"} 人工停用 ──► DISABLED
                  │                                ▲         └── 连续投递失败 ≥5 自动熔断 ────────► DISABLED
                  └── EMAIL：patch ENABLED 直启 ───┘                                          │
                                                                                                 │ 恢复（均需人工动作，无自动恢复）：
                                                                                                 │ · IM：重新 verify 成功 → ENABLED
                                                                                                 │ · EMAIL：patch ENABLED（=重新验证，fail_count 清零）
                                                                                                 │ · 曾验证且未熔断的 IM：patch ENABLED 亦可
```

- **熔断**：投递失败一次 `fail_count + 1`；连续失败 ≥5（`DeliveryEngine.BREAKER_THRESHOLD=5`）
  单语句 CAS 置 `DISABLED`（`fail_count>=5 AND status=ENABLED` 才迁移，防并发/TOFU），并给属主
  发 URGENT 站内信（type=`CHANNEL_ALERT`，标题「渠道连续失败已自动停用」；USER 面才有属主）。
- **计数清零**：verify 成功、投递成功（同时回写 `last_verify_at`）、EMAIL 熔断态重启用，三处。
- **恢复是人工的**：IM 走重新 verify（10610 文案即引导「请重新验证」）；EMAIL 走显式 patch 启用；
  系统不会自动把 DISABLED 渠道拉回 ENABLED。已 DISABLED/PENDING 的渠道在投递计划期即被过滤。

### 5.1 引擎侧限速与重试（影响渠道侧观察到的行为）

`DeliveryEngine`（配置键前缀 `nfy.runtime.engine.`，缺省值取自 `NfyProperties`）：

| 项 | 缺省 | 行为 |
|---|---|---|
| `rate-limit-per-minute` | `18` | **每渠道每分钟投递上限**（固定窗；钉钉/企微官方硬限额 20/min，出厂留余量取 18；`0`=不限）。超限的投递行归还 PENDING 延后 60s，**不计失败不进熔断** |
| `worker-count` | `4` | 跨渠道共享投递线程池（V1.0；按渠道池隔离登记排期） |
| `scan-interval-ms` / `batch-size` | `5000` / `50` | 引擎扫描周期 / 单批领取行数（SKIP LOCKED） |
| `backoff-seconds` | `"60,300,900"` | 投递失败退避 1/5/15min 重试，**单行重试 ≤3 次**（`MAX_RETRY=3`）超限置 `DEAD` |
| `reaper-interval-ms` / `sending-stale-ms` | `60000` / `600000` | 回收卡死 SENDING 行（at-least-once，渠道侧可能重复） |

渠道熔断按 `fail_count`（渠道维度连续失败）计数，与投递行 `retry_count`（单任务维度）分离；
限速归还与渠道停用归还均不增加 `fail_count`，只有真实外呼失败才 +1。DEAD 行的人工重投见
[operations 运维分册](../05-部署与运维/operations.md)。

## 6. SMS 规划口径

`SMS` 为规划中渠道（契约演进预留，受离线环境约束尚未落地）：注册时提交 `channel_type:"SMS"`
按枚举非法拒 `10100`，待有网/私服短信环境后按 ADR-0004 渠道 SPI 引 Sms4J 落地。

## 7. curl 全链示例

沿 [quickstart-5min.md](../02-快速开始/quickstart-5min.md) 起的实例（`http://localhost:9200`）；
payload 取自 IT `NfyChannelFlowTest`/`NfyChannelAdminTest` 与 E2E `l4-channels-subscriptions.spec.ts`。

```bash
TENANT_TOKEN=...   # quickstart-5min 第 5 步换取的租户 token
BASE=http://localhost:9200

# ① 注册钉钉群机器人（PENDING 落库，verify_tip 引导验证）
curl -s -X POST $BASE/nfy/api/v1/runtime/channels \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"channel_type":"DINGTALK","name":"SRE 报警群",
       "target":"https://oapi.dingtalk.com/robot/send?access_token=abcd1234",
       "secret":"SECxxxxxxxx","keyword":"通知"}'
# → {"code":0,"data":{"channel_id":"1943...","status":"PENDING","verify_tip":"请调用 verify 接口发送验证消息"}}

# ② 验证（同步外呼 ≤5s；业务码 errcode=0 才算成功）
curl -s -X POST $BASE/nfy/api/v1/runtime/channels/1943.../verify \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → {"code":0,"data":{"channel_id":"1943...","status":"ENABLED","last_verify_at":1759...}}

# ③ 列表脱敏（webhook 查询串全遮蔽；邮箱形如 a***@e2e.test）
curl -s $BASE/nfy/api/v1/runtime/channels \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → {"code":0,"data":{"list":[{"channel_id":"1943...","channel_type":"DINGTALK","name":"SRE 报警群",
#      "target":"https://oapi.dingtalk.com/robot/send?****","status":"ENABLED","fail_count":0,...}]}}

# ④ 停用 / 启用（EMAIL 直启 OK；IM 未验证或熔断态启用 → 10610）
curl -s -X PATCH $BASE/nfy/api/v1/runtime/channels/1943... \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"name":"改名后","status":"DISABLED"}'

# ⑤ EMAIL 通道（格式校验零 DNS；verify 恒 10604，patch 直启为正式契约）
curl -s -X POST $BASE/nfy/api/v1/runtime/channels \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"channel_type":"EMAIL","name":"SRE 值班邮箱","target":"ops@example.com"}'
curl -s -X PATCH $BASE/nfy/api/v1/runtime/channels/<email渠道id> \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"status":"ENABLED"}'

# ⑥ 租户公共渠道（admin 面：无 X-User-Id；同款脱敏列表；公告 channel_ids 引用后外发）
curl -s -X POST $BASE/nfy/api/v1/admin/channels \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"channel_type":"DINGTALK","name":"公共报警群",
       "target":"https://oapi.dingtalk.com/robot/send?access_token=pub-ach-1","secret":"SECx"}'
curl -s $BASE/nfy/api/v1/admin/channels -H "Authorization: Bearer $TENANT_TOKEN"
```

注册成功且 ENABLED 后，渠道进入订阅矩阵可选集——勾选规则见 [subscription.md](subscription.md)；
发送侧如何按矩阵展开见 [sending.md](sending.md) 与
[技术白皮书 §4](../01-概览/technical-whitepaper.md)；错误码总表见
[error-codes.md](../04-参考/error-codes.md)。
