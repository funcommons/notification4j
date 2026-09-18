# notification4j 测试用例清单

> 生成方式：从代码实跑数据提取，非人工估算。IT 用例数 = surefire 实跑报告（`backend/notification4j-it/target/surefire-reports/`）与逐线实跑汇总（`frontend/e2e-regression/.runs/it-summary.txt`）双源核对；E2E 用例名从 spec 文件 `test()` 标题逐条提取。静态 grep `@Test` 计数 ≠ 实跑数（同行注解会漏、TCK 继承用例不在本文件），以实跑为准。
> 基线：2026-09-18（v1.2.2，缺陷台账清零后基线；单元层 starter 411 / client-starter 10 另见 `test-plan.md` §3）。

## 一、IT 套件 × 用例数（28 套件 / 129 用例）

套件目录：`backend/notification4j-it/src/test/java/fun/commons/notification4j/it/`。业务线归属按 `bin/run-it-lines.sh` 分组（与 it-summary 逐线合计一致）。

| # | 套件 | @Tag | @Order | 业务线 | 覆盖内容（套件 javadoc 提炼） | 用例数（实跑） |
|---|---|---|---|---|---|---|
| 1 | NfySchemaMigrationTest | step1 | — | L7 | 评审定稿 DDL 在真 PG 上可执行迁移 | 6 |
| 2 | NfyTenantComplianceTest | step1 | — | L6 | framework4j-tenant TCK 合规闸（列集契约/唯一索引/tenant_id 打头；8 例继承自 `TenantComplianceSuite`，本文件无 @Test 注解） | 8 |
| 3 | NfyAuthFlowTest | step2 | — | L1 | 认证闭环：换 token/防爆破锁定/停用拒发/平台租户型别互打隔离 | 8 |
| 4 | NfyMessageFlowTest | step3 | — | L2 | 消息发送 + 站内信闭环 | 6 |
| 5 | NfyChannelFlowTest | step4 | — | L4 | 渠道域：SSRF 白名单/列表脱敏/验证语义/启停/删除防探测 | 12 |
| 6 | NfySubscriptionFlowTest | step4 | — | L4 | 订阅矩阵（API-SUB-001/002） | 7 |
| 7 | NfyAnnouncementFlowTest | step5 | — | L3 | 公告域 runtime 面（API-ANN-001~003） | 4 |
| 8 | NfyAnnouncementAdminTest | step5 | — | L3 | 公告管理面（API-AAN-001~005） | 6 |
| 9 | NfyDeliveryPlanTest | step6 | — | L5 | 投递计划：send/publish → nfya_delivery PENDING 行 | 4 |
| 10 | NfyDeliveryEngineTest | step6 | 方法级 @Order ×3 | L5 | 外发引擎（DB 队列 + SKIP LOCKED + 线程池） | 3 |
| 11 | NfyNotifyClientTest | step8 | — | L2 | NotifyClient 双模式门面（local 分支集成） | 2 |
| 12 | NfyPlatformDomainTest | step8 | — | L6 | 平台域：强制制订阅 + 平台公告（API-PTE-005/PAN-001~004） | 4 |
| 13 | NfyRemoteClientUnitTest | step8 | — | L1 | RemoteNotifyClient 纯单元（fake transport，不起 Spring） | 3 |
| 14 | NfyChannelAdminTest | step10 | — | L4 | 公共渠道管理面（API-ACH-001~003；scope 隔离） | 2 |
| 15 | NfySignatureKeyTest | step11 | — | L6 | 签名密钥管理（API-SEC-001） | 2 |
| 16 | NfyPlatformTenantTest | step12 | — | L6 | 租户生命周期（API-PTE-001~004） | 3 |
| 17 | NfyRegistrationKeyFlowTest | step13 | — | L1 | 注册码闭环：平台签发 + 开放域自助注册 | 2 |
| 18 | NfyQueryCompletionTest | step14 | — | L2 | 查询补全（MSG-003/005/008 + DICT-001 + STAT-001 + PST-001） | 5 |
| 19 | NfyBatchSendJobTest | step15 | — | L2 | 批量发送与 Job 轮询（API-MSG-002 + JOB-001） | 3 |
| 20 | NfyTemplateTest | step16 | — | L7 | 模板域（API-TPL-001/002/003） | 2 |
| 21 | NfyOpsHealthTest | step17 | — | L7 | 运维健康检查（/nfy/api/v1/ops/health） | 1 |
| 22 | NfyReviewGapTest | step18 | — | L1 | 评审登记 IT 缺口补齐（第 10~16 步评审 P2-8） | 5 |
| 23 | NfyMessageCancelTest | step19 | — | L2 | 消息撤回（API-MSG-009，ADR-0010 竞态安全语义） | 5 |
| 24 | NfyQuietHoursTest | step20 | — | L5 | 免打扰时段 quiet_hours（API-SUB-001/002 扩展） | 8 |
| 25 | NfyClientStarterTest | step30 | — | L1 | client-starter 跨进程 remote 接入 + 签名协议等价回归闸 | 3 |
| 26 | NfySignatureFaceTest | sigface | — | L1 | D-1/D-2 签名面活体防回归（TDD 先红后绿） | 2 |
| 27 | NfySmokeTest | smoke | 方法级 @Order(1~8) | L8 | 冒烟顺序链 8 步（换 token→发消息→已读→订阅→公告→撤回→投递运维→健康） | 8 |
| 28 | NfyStaticPageTest | （无标签） | — | L8 | 静态页托管：SPA 入 starter jar + history 路由 fallback | 5 |

**逐线合计（与 it-summary 实跑一致）**：L1 23 ｜ L2 21 ｜ L3 10 ｜ L4 21 ｜ L5 15 ｜ L6 17 ｜ L7 9 ｜ L8 13 ｜ **合计 129**。

标签说明：`stepN` 对应编码步序；`sigface` 为签名面防回归专项；`smoke` 全仓唯一，`-Dgroups=smoke` 仅命中 NfySmokeTest；NfyStaticPageTest 无标签（不参与分组过滤，仅随 L8 组 `-Dtest` 指定执行）。

## 二、E2E 规范 × 用例（8 spec / 70 用例）

spec 目录：`frontend/e2e-regression/l*.spec.ts`；执行于出厂等价实例 `:9200`，`workers: 1` 串行。证据形态按第 2 轮（2026-09-18-01）口径注明：**UI 关键图**（真实界面截图）/ **API 证据页**（产品无 UI 面的渲染证据页）。

### l1-auth.spec.ts — L1 认证与开放接入线（8 例；证据：API 证据页 ×8，产品无 UI 面）

1. L1-01 AUTH-001 平台 client_credentials 换 token 成功
2. L1-02 AUTH-001 新建租户凭据换 token 端到端
3. L1-03 AUTH-001 错误 secret 拒绝
4. L1-04 AUTH-001 防爆破：连错 5 次后锁定，正确凭据也被拒
5. L1-05 PRK-001 注册码签发 + 列表脱敏
6. L1-06 OPEN-001 注册码自助注册成功 → 新租户可认证
7. L1-07 OPEN-001 max_uses=1 注册码消费后二次注册拒绝（原子扣减）
8. L1-08 OPEN-001 max_uses=2 注册码恰好消费两次

### l2-messages.spec.ts — L2 站内消息线（6 例；证据：UI 关键图 ×5 + API 证据页 ×4）

1. L2-01 MSG-001 INAPP 发送成功（message_id/biz_no/receiver_count/inapp_saved）
2. L2-02 MSG-001 biz_no 幂等闸：同租户同 biz_no 二次发送 10401 拒绝
3. L2-03 MSG-002/JOB-001 批量异步：提交返 job_id，轮询至 DONE 且成功数=30
4. L2-04 MSG-004 Cursor 列表：limit 翻页/has_more/next_cursor 无重复；type_code 服务端过滤；keyword 为前端本地过滤边界
5. L2-05 MSG-006 详情即已读 + MSG-007 未读数联动递减
6. L2-06 MSG-009 撤回：已读/未读均可撤（ADR-0010 双条件 UPDATE），用户侧不可见+幂等

### l3-announcements.spec.ts — L3 公告线（6 例；证据：UI 关键图 ×3 + API 证据页 ×4）

1. L3-01 ANN-001 平台公告(tenant 0)+租户公告合并生效列表，published_at 倒序，草稿不可见
2. L3-02 ANN-002 markRead 两次调用幂等，my_status→READ，unconfirmed 不因阅读变化
3. L3-03 ANN-003 confirm 幂等 + unconfirmed_count 恰好减 1
4. L3-04 AAN 租户公告管理流：DRAFT→发布→runtime 可见；biz_no 幂等 10401；下线即不可见
5. L3-05 PAN 平台公告确认回执跨域口径（第 28 步修复回归）：平台侧 detail confirm_count≥1
6. L3-06 PTE-005 mandatory 唯一入口：仅平台域可设，租户域接口不暴露

### l4-channels-subscriptions.spec.ts — L4 渠道与订阅线（13 例；证据：UI 关键图 ×4 + API 证据页 ×10）

1. L4-01 CHN-001 自注册渠道成功（PENDING 落库不发验证消息）
2. L4-01b IM 官方域名注册（环境敏感）：代理 fake-IP DNS 命中 SSRF 禁用集 → 10609
3. L4-02 SSRF 防线：明文/内网/非白名单/非443端口 → 10609
4. L4-03 非法渠道类型/空 target/非法邮箱/名称超长 → 10100
5. L4-04 CHN-002/003 列表脱敏 + 验证语义（无凭据 10604 保持 PENDING）
6. L4-05 CHN-004 patch 改名/启停流转
7. L4-06 CHN-005 删除渠道 + 防探测（删后同码操作 10400）
8. L4-07 ACH 公共渠道：scope=TENANT 隔离 + 验证/管理
9. L4-08 SUB-001 矩阵三段（types/available_channels/items）
10. L4-09 SUB-002 PUT 全量替换（多类型×渠道条目，幂等，INAPP 哨兵恒首位）
11. L4-10 强制集校验：有实例须保留（10606），无实例豁免
12. L4-11 SUB 非法输入：10101 / 10601 / 10400
13. L4-12 quiet_hours 保存/回显/缺省重置/非法 10100

### l5-engine.spec.ts — L5 外发引擎线（7 例；证据：UI 关键图 ×7 + API 证据页 ×2）

1. L5-00 修复回归 ND-L5-01：EMAIL 渠道纯 API 通路 ENABLED 可达（原 P1 缺陷）
2. L5-01 外发成功链：类型默认 EMAIL → 计划 → 引擎 → SMTP 收包
3. L5-02 失败重试退避：454×2 → FAILED(1,2) 递进 → 恢复后 SENT
4. L5-03 持续失败 → DEAD（retry_count=4）→ DLV-002 人工重投成功
5. L5-04 quiet_hours：静默窗内投递推迟（PENDING + next_retry_at=窗结束）
6. L5-05 渠道熔断前夜：4 次失败（未达阈值不熔断）→ DEAD
7. L5-06 渠道熔断：第 5 次失败 → DISABLED + 属主站内信兜底 + EMAIL 熔断重启用=重新验证

### l6-platform.spec.ts — L6 平台治理与租户生命周期线（10 例；证据：UI 关键图 ×1 + API 证据页 ×10）

1. L6-01 PTE-001 建租户：secret 仅此一次返回 + 响应不含内部数字 id + 可认证
2. L6-02 PTE-001 email 唯一约束：同邮箱重复创建 10401 拒绝
3. L6-03 PTE-002 配置修改 + 详情 email 脱敏 + 列表可见
4. L6-04 PTE-004 SUSPEND：新认证 401 同码防探测 + 存量 token 双面 10201 阻断
5. L6-05 PTE-004 RESUME → ACTIVE：认证恢复
6. L6-06 PTE-003 reset-secret：新 secret 立即生效 + 旧 secret 24h 宽限（§5.5 双版本过渡）
7. L6-07 PTE-004 CLOSE 终态：再迁移 10402 + 认证不可恢复 + 非法 action 10100
8. L6-08 PST-001 平台跨租户概览：造数据后字段齐全与计数单调合理
9. L6-09 平台域闸：租户 token 打平台面端点 → 403 拒绝
10. L6-10 PTE-005 强制订阅唯一入口：平台域 type-mandatory 设置 + 参数/租户校验

### l7-admin.spec.ts — L7 Admin 运营治理线（12 例；证据：API 证据页 ×12，产品无 UI 面）

1. L7-01 TYP-001 建类型：default_channels=[INAPP] 落库并列表回显
2. L7-02 TYP-002 更新类型 + 非法枚举 10100
3. L7-03 TYP→MSG 链路：类型发消息 → 站内信落库/未读/详情即已读
4. L7-04 ACH-001 注册公共渠道（EMAIL）→ 列表可见且 target 脱敏
5. L7-05 ACH 非法 target 拒绝：渠道枚举 10100 / SSRF 白名单 10609 / 邮箱格式 10100
6. L7-06 DLV-001 投递查询：租户隔离 total + status/channel_type/biz_no 过滤契约
7. L7-07 SEC-001 密钥轮换：状态脱敏 → rotate → prev 宽限字段 → 短 secret 10100
8. L7-08 STAT-001 租户概览：created_at 时间基计数 + 空数据日成功率 0
9. L7-09 TPL-001/002 模板创建/列表/详情/更新 + code 租户内唯一 10401
10. L7-10 TPL-003 渲染预览：全参渲染 + 渠道覆盖 + 缺参 10603 + 未知模板 10400
11. L7-11 模板发送渲染时机观察：直发路径内容透传（渲染仅 preview，V1.1 模板发送未接线）
12. L7-12 OPS 健康检查：免 token 可达，db/redis 全 UP

### l8-frontend.spec.ts — L8 前端消息中心线（8 例；证据：UI 关键图 ×17 + API 证据页 ×2）

1. L8-01 嵌入握手全链：host 页 NFY_TOKEN → 消息列表渲染种子数据
2. L8-02 五页导航标志性元素 + SPA fallback
3. L8-03 公告页展示与已读/确认回执（UI 操作 + API 复核）
4. L8-04 铃铛未读角标 = unread-count total（bell 单页壳经真实宿主页 src 切换嵌入）
5. L8-05 消息已读联动：UI 标为已读 → 角标递减 + 列表样式切换 + API 复核
6. L8-06 撤回展示：API 撤回已发消息 → UI 刷新后不再展示
7. L8-07 origin 白名单拒绝：非白名单父页投递 NFY_TOKEN 不被接受
8. L8-08 无效 token 表现：数据面鉴权失败 → 会话失效重连提示（F-2 修复后行为）

## 三、用例总数

| 层 | 规模 |
|---|---|
| IT 集成回归（28 套件，含冒烟 8 例与 TCK 继承 8 例） | **129** |
| E2E（8 spec） | **70** |
| **合计（IT + E2E）** | **199** |

另：单元层不计入本清单——starter 411 + client-starter 10 用例，见 `test-plan.md` §3 与各模块 surefire 报告。
