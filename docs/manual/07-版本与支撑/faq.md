# 常见问题（FAQ）：选型 / 接入 / 前端 / 运维 / 版本

> notification4j 使用手册 · 07-版本与支撑第 2 篇 · 版本口径 **v1.4.0**（Maven `1.4.0` / tag `v1.4.0`）。
> 答案逐条对齐已评审各篇与源码，「已知限制」类如实声明实装状态、不做承诺性表述。
> 单点故障深挖走 [troubleshooting.md](../06-最佳实践/troubleshooting.md)；错误码全表见
> [error-codes.md](../04-参考/error-codes.md)；升级口径见 [upgrade-guide.md](upgrade-guide.md)。

## 1. 选型类

**Q1：三种接入形态怎么选？**
速查（[product-overview §4](../01-概览/product-overview.md)）：最低延迟 / 最高吞吐 → 嵌入 `local`；
只发消息、不引 MyBatis/PG/Redis 数据面依赖 → 跨进程 `remote`（client-starter）；多业务方共享、
异构语言客户端、升级中台不影响业务方 → 独立部署（fat jar，出厂端口 9200）。三形态业务代码同接口
（`NotifyClient` 同 FQCN），切换只换依赖坐标与配置、零改码。

**Q2：全量 starter 与 client-starter 能同时引吗？**
不能，二选一（互斥契约）。万一同 classpath：全量 starter 的 Bean 确定性优先（client-starter
`@ConditionalOnMissingBean` 让位），行为仍正确，但启动打 WARN 提示移除其一——不做启动 fail-fast
（[quickstart-remote §2](../02-快速开始/quickstart-remote.md)）。

## 2. 接入类

**Q3：换 token 的 client_id 到底填什么？**
租户 **open_id**（PTE-001 / OPEN-001 响应返回的 12 字符 OpenID 混淆串），不是租户 name、
不是内部雪花 id（原始 id 数字串与 name 也被向后兼容接受，但推荐 open_id）。平台身份用出厂字面量
`PLATFORM`（env `PLATFORM_CLIENT_ID` 可改）。见 [auth.md §2.1](../03-开发指南/auth.md)。

**Q4：为什么重发同样内容返回 10401？**
`(tenant_id, biz_no)` 唯一索引是真闸：同租户同 `biz_no` 再发 → `10401`「业务号重复，幂等拒绝」。
这不是故障——上次已受理，**原样重发即拿回首次结果**（MSG-003 查发送结果）；要发新内容必须换新
`biz_no`；撤回后同 biz_no 重发同样 10401。见 [sending.md §3](../03-开发指南/sending.md)。

**Q5：批量发送为什么不在幂等面（Idempotency-Key）？**
`Idempotency-Key` 拦截只覆盖 messages / channels / subscriptions 三组路径（app yml
`framework4j.idempotency.path-patterns`），批量端点不在内——同 body 重复提交会生成**两个 job**。
批量幂等靠 `biz_no`：显式传值重提时首批撞 10401 计入 failed_items（终态 PARTIAL）；重提请换号。
见 [sending.md §6](../03-开发指南/sending.md)。

**Q6：限流为什么感觉这么严？100 次/分钟谁在共享？**
出厂 100 次/分钟，**维度实际是客户端 IP（每路径独立桶）**、不是租户：yml `default-scope: APP`
（大写）未命中 fwk4j v1.7.1 仅认的小写 `ip/user/app/global` 分支，落 default → 按 IP 限流
（key=`notification4j:rl:APP:{ip}:{path}`；yml「按 tenant_id」注释为陈旧口径）。同一出口 IP /
NAT 后的多个用户**共享一个桶**，易集体触顶 HTTP 429 + `10500`。按 `Retry-After` 退避；持续触顶
调 `framework4j.rate-limit.default-limit`（勘误见 [configuration.md §9](../05-部署与运维/configuration.md)）。

**Q7：remote 模式调 unreadCount / listMessages 为什么报 10101？**
这两个在服务端是 T+U 端点（须 `X-User-Id` 头，缺失 → `10101`「X-User-Id 不能为空」），而当前
`RemoteNotifyClient` **不注入该头**（`userid` 参数同样被忽略）。跨进程按用户视角查询：自定义
`HttpTransport` 装饰器补头，或交给嵌入的消息中心 SPA 面。见
[quickstart-remote §5](../02-快速开始/quickstart-remote.md)。

**Q8：token 响应里的 expires_in 为什么是字符串 `"28800"`？**
fwk4j-web 信封的 Long→String 序列化契约（防 JS 精度丢失）：ID / 雪花 / 秒数一律字符串，连
`expires_in` 也是。客户端别用数值比较处理，先 `parse`。详见
[error-codes.md §1](../04-参考/error-codes.md)。

## 3. 前端类

**Q9：origin 白名单的「两半」分别怎么配？**
面 1 构建时 `VITE_NFY_PARENT_ORIGINS`（逗号分隔；改名单须重打 dist——随仓发布的出厂 dist 构建期
零 `VITE_*` env，生产宿主 origin 实际都走面 2）；面 2 运行时租户 `oem.hosts`（平台域 PTE-002
PATCH，运营时可配、无需重发前端）。双面 ∪，任一命中即接受，都不命中 fail-closed 静默忽略。
origin 为 scheme+host+port **精确匹配**（无路径；协议/端口不一致都不命中）。见
[frontend-embed §4](../03-开发指南/frontend-embed.md)。

**Q10：铃铛「查看全部」点了没反应（NFY_NAVIGATE）？**
`NFY_NAVIGATE` 在 jar 托管 iframe 形态**永不发出**——bell 路由未透传 `jumpUrl` props，仅
「组件直用」形态（宿主工程直接引 `BellPage` 组件并设 props）可发出。父页可先按协议预留监听，
勿依赖其发出。见 [frontend-embed §2.2/§7.3](../03-开发指南/frontend-embed.md)。

**Q11：URGENT 未读为什么收不到 NFY_URGENT 提示？**
协议已实现、**后端字段待补**：`unread-count` 响应当前只有 `unread_count / unconfirmed_count /
total` 三计数、无 `has_urgent` 字段，故该消息当前实际不发出。见
[frontend-embed §2.2](../03-开发指南/frontend-embed.md)。

## 4. 运维类

**Q12：健康端点为什么 DOWN 了 HTTP 还是 200？**
`GET /nfy/api/v1/ops/health` 控制器恒走 success 信封：DOWN 时 HTTP 仍 200、信封 `code` 仍 0。
判健康**只看 `data.status`**（探针 db=`SELECT 1`、redis=`PING`，任一 DOWN 即整体 DOWN），
探活脚本必须解析 body。见 [operations.md §1](../05-部署与运维/operations.md)。

**Q13：历史数据怎么清？retentionDays 配了怎么没用？**
`config.retentionDays` **未实装**：PTE-002 可写进租户 config jsonb，但后端没有任何清理任务消费它
（仅存储的预留配置）。历史数据保留需 DBA 自管（按 `created_at` 归档/删除；`nfyp_audit_log` 保持
append-only，勿 UPDATE 历史行）。增长最快的表与巡检 SQL 见
[operations.md §7](../05-部署与运维/operations.md)。

**Q14：怎么接 Prometheus / 做监控告警？**
Metrics **未实装**（backend 全部 pom 零 micrometer 依赖，无 `/actuator/metrics`、无 Prometheus
暴露面）。当前可观测面四块：health 端点、结构化日志（trace_id 三贯通）、DB 投递表巡检、租户/平台
统计端点；最小告警组合（health 非 UP / PENDING 积压 / DEAD 突增 / 429 集中）见
[operations.md §6](../05-部署与运维/operations.md)。

## 5. 版本类

**Q15：Maven 坐标为什么有两种写法？**
同一构件两种引入方式：JitPack `com.github.funcommons.notification4j:notification4j-starter:v1.4.0`
（三方推荐，按 tag）与内部仓 `fun.commons.notification4j:notification4j-starter:1.4.0`（本地
`cd backend && mvn install` 后可用）。自 v1.3.0 起 Maven 版本号与 tag 对齐（此前 pom 停留在
1.0.0），当前双口径指向同一构件。见 [onboarding §1](../03-开发指南/onboarding.md)。

**Q16：JitPack 首次拉取为什么慢 / 404？**
JitPack 按 tag 触发远程构建（常见 1~3 分钟排队），失败先看构建日志
`https://jitpack.io/com/github/funcommons/notification4j/v1.4.0/build.log`；急用可本地构建安装后
改用内部坐标。见 [quickstart-embed §2/§8](../02-快速开始/quickstart-embed.md)。

**Q17：为什么 v1.2.2 及更早 tag 在 JitPack 上构建失败（Error）？**
`jitpack.yml`（显式 JDK 17 + Maven 3.9.9 + `-f backend/pom.xml`）**晚于 v1.2.2 tag 才入库**：
更早 tag 走 JitPack 默认构建链（默认 JDK 8 跑不动 Spring Boot 3.x）必然 Error。**请用 v1.3.0+**；
跨版本升级见 [upgrade-guide.md](upgrade-guide.md)。

**Q18：SMS 短信渠道什么时候支持？**
SMS 为规划渠道（ADR-0004 渠道 SPI 预留，拟引 Sms4J），受离线环境约束**尚未落地、无时间表**。
当前注册提交 `channel_type:"SMS"` 按枚举非法拒 `10100`。见 [channels.md §6](../03-开发指南/channels.md)。

## 6. 已知限制汇总（v1.4.0 如实声明）

| # | 项 | 状态 | 影响 / 替代 |
|---|---|---|---|
| 1 | `retentionDays` | 未实装（仅存储） | 清理 DBA 自管（operations.md §7） |
| 2 | Metrics / Prometheus 面 | 未实装 | health + 日志 + DB 巡检组合（operations.md §6） |
| 3 | SMS 渠道 | 未落地、无时间表 | 注册报 10100（channels.md §6） |
| 4 | `NFY_URGENT` 消息 | 后端字段待补，实际不发出 | 父页预留监听即可（frontend-embed §2.2） |
| 5 | `NFY_NAVIGATE`（iframe 形态） | 永不发出（jumpUrl 未透传路由） | 组件直用形态可设（frontend-embed §7.3） |
| 6 | remote 模式 unreadCount / listMessages | 不注 X-User-Id（报 10101） | 自定义 transport 补头或走 SPA 面 |
| 7 | `NotifyClient` javadoc 写 `nfy.client.enabled` | 陈旧注释 | 真实键 `nfy.runtime.client-enabled`（configuration.md §3.1） |
| 8 | JitPack v1.2.2 及更早 tag | 构建失败（早于 jitpack.yml） | 用 v1.3.0+ |
| 9 | 引擎限速 `rate-limit-per-minute` | 单机内存固定窗 | 多实例部署合计会超渠道限额（operations.md §2） |
| 10 | 公告并发双发布 | 软限竞态（登记边界） | ≤20 上限按终态计数（announcements.md §5） |

---

> 取证源：手册已评审各篇（product-overview / quickstart-embed / quickstart-remote / auth / sending /
> channels / frontend-embed / onboarding / announcements / error-codes / configuration / operations）、
> [api-spec](../../api/api-spec.md)、`backend/` 源码（`NotifyClient` javadoc、`NfyAutoConfiguration`、
> `ClientAutoConfiguration`、app `application.yml`）、`jitpack.yml`、`CHANGELOG.md`。
