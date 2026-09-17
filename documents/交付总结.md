# notification4j V1.0 交付总结

> 2026-09-17 · 编码第 1~18 步（V1.0）+ 收官轮 + 第 19~21 步（V1.2：消息撤回、免打扰、重投 UI）+ 第 22~23 步（质量包与终审）+ 第 24~28 步（四层测试体系）+ 第 29~30 步（部署形态三件套）· 按方案「TDD→实现→修复→评审循环（4 轮或无 P0~P2）」执行

## 一、交付范围（对照接口设计文档附录 A）

| 域 | 交付 | 关键能力 |
|---|---|---|
| 认证/开放域 | AUTH-001、OPEN-001 | client_credentials 换 token（防爆破 5 次/15min）、注册码自助注册（原子扣减） |
| runtime 消息 | MSG-001/002/003/004/005/006/007/008、JOB-001 | 发送（biz_no 幂等+订阅矩阵外发计划）、批量异步 Job、send-results、Cursor 列表、详情即已读、未读合成 |
| runtime 公告 | ANN-001/002/003 | 平台+租户合并生效列表、阅读/确认幂等回执、confirm_count 原子计数 |
| runtime 渠道/订阅 | CHN-001~005、SUB-001/002 | 自注册（SSRF 白名单+加签验证）、订阅矩阵全量替换、强制集类型→实例校验 |
| runtime 其他 | DICT-001 | 字典下发（前端禁硬编码） |
| admin | TYP×2、AAN×5、ACH×3、DLV×2、SEC-001、STAT-001、TPL×3 | 类型/公告/公共渠道/投递重投/签名密钥轮换/概览/模板渲染 |
| platform | PTE-001~005、PAN-001~004、PRK-001、PST-001 | 租户生命周期（SUSPEND 阻断认证/CLOSE 终态）、mandatory 唯一入口、平台公告、注册码签发、跨租户概览 |
| 引擎 | SKIP LOCKED 扫描/重试退避 1-5-15min≤3 次→DEAD/渠道熔断≥5+属主站内信/reaper 回收/每渠道限速 | at-least-once 语义（§4.3） |
| 门面 | NotifyClient 双模式 | local 直连 / remote HttpTransport，业务方零感知 |
| 前端 | 消息中心四页+铃铛 | postMessage 嵌入握手（origin 白名单/内存 token/过期重握手）、未读 30s 轮询 |

**合计：50/50 接口契约实现，每一项均有 Testcontainers 集成测试。**

## 二、验证数据（第 30 步后终态，详第七、八节）

| 门 | 结果 |
|---|---|
| 后端单元测试（mock 切片，含双 check 覆盖率门槛） | **409/409**（starter）+ client-starter 10/10 |
| 后端集成回归（26 套件，含冒烟 8 + 静态页 5 + client-starter 3） | **126/126** |
| 合并口径行覆盖率（单测+IT exec） | **96.50%**，七包 100% 逐包卡死 + BUNDLE ≥96% 防回退 |
| 独立部署 fat jar | 真启动实测（Flyway 首启迁移/SPA fallback/API 不吞） |
| 前端 vitest（SDK 341 + nfy 17） | **358/358** |
| 前端 vue-tsc + eslint | 0 错误 0 警告 |

## 三、评审循环记录

| 步骤 | 评审轮次 | 结论 |
|---|---|---|
| 第 3 步（消息/站内信） | 1 轮 | P1×3+P2×7 全修（批量 insert/@Valid 接线/幂等键路径/inSql 参数化/N+1/markRead 边界/JSON 手拼/上下文抽取/偏差登记） |
| 第 4 步（渠道/订阅） | 2 轮 | P0×2（钉钉加签 key 颠倒、飞书 token 脱敏）+P1×6+P2 全修 |
| 第 5 步（公告域） | 1 轮 | P1×4（并发确认双计数→条件 UPDATE/total 口径/10611 未失效占名额/updated_at 触发器）+P2×9 全修 |
| 第 6b 步（外发引擎） | 1 轮 | P1×5（回写 CAS/出厂配置/SMTP TLS+超时/限速接线/熔断信纪元）+P2×9 全修 |
| 第 10~16 步 | 1 轮 | P0×1（租户停用后签名面同断：SecretProvider 校验 ACTIVE）+P1+P2 全修 |
| 第 17 步（ops health/迁移/租户合规） | — | NfySchemaMigrationTest×6、NfyTenantComplianceTest×8 |
| 第 18 步（评审缺口补齐） | 1 轮 | NfyReviewGapTest×5：SUSPEND 撤销存量会话回归、密钥 71/72 边界、并发扣减恰好一胜、模板 $ 字面量、stats 零数据日 |
| 收官轮（并行子代理×2） | 抽查裁决 | 前端：76 个 benefit/dev 遗留文件归档 `frontend/legacy/`（router/main/store 重写，三门复跑全绿）；后端：Channel 双面复制收敛为 `ChannelCoreService` scope 参数化 + StatsService 口径统一（时间基 created_at、空数据日成功率 0），全量 IT 96/96 复跑绿 |

## 四、关键框架口径（实测钉死，详见记忆与代码注释）

1. 平台端点 = `@PlatformDomain + @RequiresToken("TENANT")`（PLATFORM 合成 token 为 tenant_id=0 的 TENANT 型别）
2. 加密列必须走实体 typeHandler 写入（wrapper.setString 绕过加密→明文损坏）
3. Map+jsonb 字段置 null 借 MP NOT_NULL 策略从 update SET 剔除；写入用 `setSql(col = {0}::jsonb)`
4. `String.valueOf(claim)` 链式调用命中 valueOf(char[]) 重载 → CCE；先赋 Object 再 valueOf
5. PG 不支持 `UPDATE...LIMIT`（分批=查主键批+IN 更新）；Long 序列化为字符串（雪花精度）
6. 双 @ControllerAdvice 顺序未定义 → 本地只留 Auth/Security 并 @Order 最高

## 五、已知边界（全部登记于接口文档 V1.0.5~V1.2.1 修订史）

- V1.2 架构决策已按项目 ADR 实践补录：ADR-0010（消息撤回竞态安全语义——不追回已投递）、ADR-0011（免打扰计划期 next_retry_at 推迟）；ADR-0001~0011 索引与技术方案文档 §8.1 状态列已同步 accepted

- MSG-003 之前的 10401 文案、MSG-004 keyword 筛选、§5.3 Redis 水位线缓存、CHN-003 EMAIL 验证（SMTP 适配器）、注册码 Redis 扣减优化、批量 Job biz_no 批次后缀、渠道 ≤5 软限并发窗口、10611 并发软限
- 低优先级登记：STAT 深翻页性能
- ~~模板参数名 `[\w+]` 不含中文~~（第 22 步已文档化）、~~SUSPEND 撤销断言仅覆盖 admin API 面~~（第 22 步已补 runtime 面）
- 前端 `frontend/e2e/` benefit 遗留 spec 21 文件已归档至 `legacy/e2e/`（vitest exclude 已防误扫；恢复 e2e 需按 `/nfy/tenant/app/**` 重写，待 Playwright 环境）
- 文档终审（第 23 步）：接口设计文档 6 项全过；嵌入集成指南 4 处修正（五页清单/占位文案/deliveries 路由）、README 5+3 处修正（计数以第 22 步实跑 109 为准纠偏、五页、文档版本号）

## 六、待用户决策

1. **git 首次提交**（全部改动在工作区，未提交）
2. **V1.2 离线环境内可做项全部交付**：消息撤回（第 19 步，API-MSG-009）、免打扰时段 quiet_hours（第 20 步，V1.2.1 契约）、人工重投 UI（第 21 步，NfyShell 第五页「投递」+ DEAD 行重投 + DLV-001 筛选分页）；**SMS 渠道受环境约束**——ADR-0004 引 Sms4J，离线 m2 无此依赖，须在有网/私服环境落地（V1.2 唯一剩余项）
3. V1.2 遗留登记（P2~P3）：静默窗按服务端系统默认时区（单时区部署边界）；撤回 IT 未启用真引擎（条件 UPDATE 契约保证竞态安全）；已撤回 biz_no 重发仍 10401 通用文案；投递页时间窗筛选（created_after/before）API 类型已备、UI 未加（按需迭代）；投递页无权限开关（嵌入宿主按需嵌入）；SUSPEND 签名面（HMAC）无 @RequiresSignature 入站端点、IT 不可达仅存代码级保证（后续引入签名 API 可再补）
3. `frontend/legacy/`（已归档，零引用）物理删除时机

## 七、四层测试体系（第 24~28 步）

### 7.1 四层形态

| 层 | 载体 | 规模（第 28 步后） | 连跑命令（显式 cd） |
|---|---|---|---|
| L1 单元（mock 切片，不起容器） | starter `src/test`（service/controller/engine/transport 等切片） | **409/409**（399 → 409，第 28 步 +10） | `cd backend && mvn -o test -pl notification-spring-boot-starter` |
| L2 冒烟（有序链，全链路真实依赖） | `NfySmokeTest` 8 用例（@Order 1~8：换 token→发消息→未读→渠道订阅→平台公告确认→撤回→重投→health） | **8/8**（含于 L3） | `cd backend && mvn -o test -pl notification4j-it -Dtest=NfySmokeTest` |
| L3 回归（Testcontainers PG16+Redis7 全量） | it 模块 25 上下文类 | **118/118**（117 → 118，含冒烟 8） | `cd backend && mvn -o install -pl notification-spring-boot-starter -DskipTests && mvn -o test -pl notification4j-it` |
| L4 覆盖率门槛（双 check） | starter pom JaCoCo（merge 单测 exec + it exec） | 双绿（见 7.3） | 随 L1 命令尾部执行 |

纪律：改 starter main 后必 install 再跑 it；输出重定向 + `echo RC`；连跑顺序 L3 → L1（starter 合并口径消费 it 的 `jacoco-it.exec`）。

### 7.2 合并口径覆盖率（单测 exec + IT exec）

第 27 步基线 96.14%（2418/2515）→ **第 28 步 96.50%（2427/2515）**：

| 包 | 行覆盖 | 未覆盖行 | 说明 |
|---|---|---|---|
| dto/entity/kms/tracelog | 100% | 0 | 门槛一名单（精确 100%） |
| **controller** | **100%** | 0 | **第 28 步 94.1%→100%（补 9 缺口行），新入门槛一名单（六包→七包）** |
| mapper/properties | 100% | 0 | 未入名单（由 BUNDLE 兜底，避免门槛膨胀） |
| client | 99.1% | 1 | `RemoteNotifyClient#parse` 防御 catch（JVM 内不可达，类级排除） |
| util | 84.0% | 4 | `WebhookSigner` 两个 JCE catch（类级排除） |
| engine | 97.5% | 7 | — |
| transport | 95.7% | 2 | — |
| service | 96.5% | 62 | — |
| autoconfigure | 84.2% | 12 | @Conditional 装配分支，remote/traceLog 等 IT 不触达（登记跳过） |

### 7.3 双 check 门槛（第 27 步建立，第 28 步扩名单）

- **check-merged-exact（门槛一）**：合并口径下行覆盖恰为 100% 的包逐包卡死（LINE COVEREDRATIO=1.00/PACKAGE）——dto/entity/kms/tracelog/client/util + **controller（第 28 步）** 共七包；client/util 的 JVM 不可达防御 catch 按类粒度精确排除，排除类仍受门槛二兜底。
- **check-merged（门槛二）**：BUNDLE 行覆盖 ≥96% 防回退兜底（1 行 ≈ 0.04pp），当前 96.50%。
- 第 28 步实测防回退语义仍成立：向 controller 注入未覆盖行复跑 check → `Rule violated for package ...controller: lines covered ratio is 0.86, but expected minimum is 1.00` → BUILD FAILURE，验证后移除。

### 7.4 冒烟发现的真实缺陷修复清单（P1/P2）

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | transport NPE（远端响应异常路径空指针） | transport 层判空防御 + 单测回归 |
| 2 | biz_no 空白口径漂移（公告域与消息域不一致） | 空白 biz_no 一律归 ""（放弃幂等豁免，uk 谓词 `biz_no <> ''` 不拦截） |
| 3 | Stats×2（概览统计两处口径偏差） | StatsService 口径统一（时间基 created_at、空数据日成功率 0） |
| 4 | **公告 confirm_count 跨域双口径（第 28 步）**：平台公告 `tenant_id=0`，确认回执按调用方租户落库，而 admin/PAN 的 detail/stats 按公告归属域数回执 → 平台公告 confirm_count 恒 0 | 裁决「回执行归属域 = 公告归属租户（a.tenantId）」：confirm/markRead 的 findRow/insert/confirmIfPending 改按 a.tenantId 域；用户侧读状态查询（my_status、unconfirmedCount）改 `tenant_id IN (0, :tid)` 双域谓词（语义严格等价）；requireVisible 可见性（0 或本租户）不动。租户公告口径完全等价（a.tenantId==调用方），既有断言零改写全绿；冒烟链⑤注释同步 + PAN detail `confirm_count="1"` 正式断言入 NfyAnnouncementAdminTest |
| 5 | flaky 单测（偶发失败） | 稳定化（消除时钟/顺序依赖） |

### 7.5 登记跳过

- autoconfigure 12 行（@Conditional 装配条件分支，全包同理由）不设 100% 门槛，仅计入门槛二 BUNDLE 口径；
- service/engine/transport 残余未覆盖行（合计 71 行）为防御分支与异常兜底，由 BUNDLE 96% 兜底，不逐包卡死；
- mapper/properties 虽实测 100%，类少且无回退风险面，未纳入门槛一名单；
- 修前平台公告确认的存量回执（按调用方租户落库的历史行）为孤儿数据，开发期库不做迁移登记。

## 八、部署形态三件套（第 29~30 步）

| 形态 | 载体 | 接入方 | 契约要点 |
|---|---|---|---|
| 一·独立部署（第 29 步） | `notification4j-app` fat jar | 平台运维 | 前端 SPA 入 jar「免双部署」（`NfyStaticPageConfig` + `nfy.static-page.enabled`），一个进程同时服务页面与 API |
| 二·嵌入（既有） | `notification4j-starter` | 强耦合业务方 | local 进程内直调 service，含数据面（MyBatis），`@MapperScan` 约定 |
| 三·跨进程（第 30 步） | `notification4j-client-starter` | 轻量业务方 | remote HTTP 调独立部署端，零 MyBatis/JDBC/Redis/控制器/引擎，只交付 `NotifyClient` Bean |

### 8.1 第 30 步（client-starter）交付与裁决

- **提炼=复制+裁剪，全量 starter 零改动**：`NotifyClient`/`SendMessageRequest`/`AnnounceRequest`/`NfyClientException` 四件 API **同 FQCN 同形态**（嵌入 ↔ 跨进程切换只换依赖坐标，业务代码零改动）；实现件独立包 `fun.commons.notification4j.remote`：`RemoteNotifyClient`、`AuthenticatedHttpTransport`、`NfyClientProperties`（裁剪版：mode/remote-url/remote-tenant-id/remote-tenant-secret + connect/read 超时，前缀仍 `nfy.runtime`）、`ClientAutoConfiguration`、`NfyHmacSigner`、`ServiceTokenSupplier`。
- **互斥裁决（FQCN 冲突 × 零改码的权衡）**：文档化互斥 + **确定性让位**——client-starter 装配 `@AutoConfigureAfter(name=NfyAutoConfiguration)`（字符串形式，不编译依赖全量 starter）+ `@ConditionalOnMissingBean`，意外共存时全量 starter 的 Bean 确定性提供服务（行为仍正确）+ ApplicationRunner WARN 提示移除其一；**不做启动 fail-fast**——fail-fast 只能基于 classpath 共存判定，会误伤 notification4j-it 合法同引两者的测试编排。「classloader 先者胜」的静默错配经两层消解：API 四件字节形态一致（先者胜亦行为等价）+ 实现件独立包名（互不链接）。
- **轻量边界**：依赖树零 mybatis/jdbc（`mvn -o dependency:tree` 佐证）；`framework4j-accesstoken` optional（类缺席 → `@ConditionalOnMissingClass` 分支 / Bean 缺席或生成失败 → 仅 HMAC 签名，三重降级）；签名协议本地化 `NfyHmacSigner` 与 framework4j-signature `SignatureUtil` **逐字节等价**（it 防漂移闸）——本地化理由：framework4j-signature 传递 framework4j-web/redis，违背零中间件定位。
- **装配契约**：闸 `nfy.runtime.client-enabled=true`（与全量 starter 同键，yml 切换零改动）；`remote-url` 缺失 / `mode=local` 启动即失败（Assert，与全量 starter §5.3 口径一致）；未配租户凭据不签名不 NPE（第 26 步 P1 修复的提炼保留）；delegate RestTemplate 默认 5s/10s 超时（业务方自有唯一 Bean 原样复用）。
- **验证**：client-starter 自身单测 10/10（ApplicationContextRunner：组包/降级/让位/fail-fast/超时接线/签名自洽）；it 新增 `NfyClientStarterTest`×3（SignatureUtil 逐字节等价、双 starter 共存让位 runner + 真实 imports 端到端）；全量 IT **126/126**（123→126）；starter 单测 409/409 + 双 check 不破；jacoco 不为 client-starter 设 check 门槛（check 绑定全量 starter 模块），父 pom prepare-agent+report 自然覆盖。

### 8.2 业务方接入决策树

第 30 步后：要页面/数据面治理 → 形态一；深度嵌入已有 PG 应用 → 形态二；只发消息/公告、跨进程调平台 → 形态三（坐标/最小配置/互斥契约见 backend/README.md 部署节「跨进程接入形态」）。
