# notification4j 测试计划

> 版本：V1.0 基线 ｜ 更新：2026-09-21 ｜ 依据代码实跑口径（surefire/it-summary/playwright）生成
> 参照 GB/T 9386《计算机软件测试文档编制规范》要素裁剪：保留测试策略、范围、环境、通过标准、缺陷分级与回归纪律，省略与单仓库迭代不匹配的详细进度/人员编制章节。

## 1. 目的与对象

本文定义 notification4j（站内消息/公告/站外通知多租户微中台，后端 Spring Boot starter + 前端嵌入 SPA）的测试体系：分层策略、环境、证据规约、通过标准与回归纪律。测试资产全部随仓维护，测试文档以代码实跑数据为准。

## 2. 引用文档

| 文档 | 位置 |
|---|---|
| 测试分层与覆盖率门槛口径 | `backend/README.md` §测试分层、§覆盖率口径与防回退门槛 |
| 用例清单 | `docs/test/test-cases.md` |
| 业务线测试报告 | `docs/test/report/<轮次>/`（如 `2026-09-18-01/`） |
| 缺陷台账（历史轮次） | `documents/test-report/`（第 1 轮统一测试报告 §八） |
| E2E 执行入口 | `frontend/e2e-regression/playwright.config.ts`、`bin/run-it-lines.sh`、`bin/start-app.sh` |

## 3. 测试策略（四层体系）

| 层 | 内容 | 规模（实跑口径） | 命令 |
|---|---|---|---|
| L-A 单元层 | starter 纯单测（Mockito/独立上下文，无容器） | **421** 用例（notification-spring-boot-starter）；另有 client-starter `NfyClientStarterUnitTest` **10** 用例（fake transport 纯单元） | `cd backend && mvn -o test -pl notification-spring-boot-starter` |
| L-B 冒烟层 | 一条顺序链 **8** 用例跑通核心业务闭环：`NfySmokeTest`（全仓唯一 `@Tag("smoke")`，`@TestMethodOrder(OrderAnnotation)` 方法级 `@Order(1~8)`，冷启动 ≤90s） | 8/8 | `mvn -o test -pl notification4j-it -Dgroups=smoke` |
| L-C 集成回归层 | it 模块全量：**30 个套件 / 136 用例**（Testcontainers 真库；按八业务线分组串行执行） | 136/136（L1 23 · L2 21 · L3 10 · L4 21 · L5 15 · L6 20 · L7 9 · L8 17） | `bash frontend/e2e-regression/bin/run-it-lines.sh`（逐线）或 `mvn -o test -pl notification4j-it`（全量） |
| L-D E2E 层 | Playwright 八业务线：**8 个 spec / 72 用例**，对出厂等价实例全链路验证 | 72/72（L1 8 · L2 6 · L3 6 · L4 13 · L5 7 · L6 10 · L7 12 · L8 10） | `npx playwright test -c e2e-regression/playwright.config.ts` |

冒烟链与常规 IT 纪律的刻意差异：常规 IT 用例相互独立、禁顺序依赖；冒烟链本质是「一条业务事务」（换 token → 建类型发消息 → 读 → 订阅 → 公告 → 撤回 → 投递运维 → 健康），前步产物即后步输入，定位是快速健康证明而非覆盖工具。覆盖由 L-A + L-C 负责。

**覆盖率双门槛（防回退，绑定 starter build 的 `jacoco:check`）**：

- 口径：starter 单测 exec + IT exec（`notification4j-it/target/jacoco-it.exec`）**合并**（MockMvc 全链路覆盖的 controller/autoconfigure/engine 调度线程计入），check 基于 `jacoco-merged` 报告。
- 门槛一：`dto / entity / kms / tracelog / client / util / controller` 七包行覆盖 **100%**（`RemoteNotifyClient`、`WebhookSigner` 两个纯防御 catch 类按类精确排除，仍受门槛二兜底；pom `check-merged-exact` 为准）。
- 门槛二：BUNDLE 行覆盖 ≥ **96%**（合并口径实测 96.14%）。
- 新鲜度防回退：合并取「最近一次 IT 运行」的 exec；改 starter 后先跑 IT（或中间 install 带 `-Djacoco.skip=true`），否则 check 刻意拦红提示重跑。

## 4. 测试范围（八业务线）

| 线 | 名称 | 范围定义 |
|---|---|---|
| L1 | 认证与开放接入 | client_credentials 换 token、防爆破锁定、注册码签发/脱敏/自助注册（原子扣减 max_uses）、跨进程接入（client-starter）、签名面防回归（sigface） |
| L2 | 站内消息 | 定向发送、biz_no 幂等闸、批量异步 Job、Cursor 列表/过滤、已读与未读数联动、撤回（ADR-0010 双条件 UPDATE 竞态安全语义） |
| L3 | 公告 | 平台/租户公告合并生效列表、已读/确认回执幂等、租户公告管理流（DRAFT→发布→下线）、mandatory 唯一入口 |
| L4 | 渠道与订阅 | 渠道注册（SSRF 白名单/域名禁用集）、列表脱敏、验证语义、启停/删除防探测、公共渠道 scope 隔离、订阅矩阵全量替换、强制集校验、quiet_hours 保存/回显 |
| L5 | 外发引擎 | 投递计划→DB 队列（SKIP LOCKED）→引擎执行、SMTP 收包、失败退避（FAILED 递进）、DEAD 与人工重投、静默窗推迟、渠道熔断（DISABLED + 属主站内信兜底 + 重启用重新验证） |
| L6 | 平台治理与租户生命周期 | 建租户（secret 一次性返回）、email 唯一、SUSPEND/RESUME/CLOSE 生命周期、reset-secret 双版本宽限、平台跨租户概览、平台域闸、平台站内信查询（PPM 跨租户列表/详情+read_count）、tenant TCK 合规、签名密钥管理 |
| L7 | Admin 运营治理 | 消息类型 CRUD、公共渠道管理、投递查询过滤契约、密钥轮换、租户统计概览、模板 CRUD/渲染预览、运维健康检查、DDL 迁移 |
| L8 | 前端消息中心 | 静态页托管与 SPA fallback、iframe 握手全链、五页导航、公告已读/确认 UI、铃铛未读角标联动、撤回展示、origin 白名单、无效 token 会话失效、冒烟链（随 L8 组执行） |

范围外：多实例水平扩展压测、MQ 演进形态（ADR-0003 触发条件未到）、第三方渠道真实外呼（E2E 以本地 SMTP/GreenMail 或桩承接）。

## 5. 测试环境

### 5.1 后端四层（L-A~L-C）

- Testcontainers：`postgres:16` + `redis:7-alpine`，Flyway `classpath:db/migration` 全量迁移，MockMvc 全链路请求；用例级数据隔离见 §8 回归纪律。
- 依赖离线模式：本机 m2（`mvn -o`），命令显式在 `backend/` 目录执行。

### 5.2 E2E 层（L-D）

- 被测实例：**出厂等价 fat jar**，`bin/start-app.sh` 起于 `http://localhost:9200`（出厂偏差已由 app application.yml 修复取代——签名面 `path-patterns=[]`、druid `filters: stat,slf4j`、redisson 排除即出厂默认，E2E 不做任何环境覆盖）。共享容器 `nfy4j-e2e-pg` / `nfy4j-e2e-redis`（`docker start` 拉起）。
- 前置校验：`global-setup.ts` 探测 `/nfy/api/v1/ops/health`，未启动即报错并提示 `bin/start-app.sh`。
- 执行形态：`workers: 1` 串行（共享实例防数据串扰）、`retries: 0`。

### 5.3 证据规约（`NFY_EVIDENCE_DIR`）

- 证据目录环境变量参数化，落 `documents/test-report/<轮次>/screenshots/`（如 `NFY_EVIDENCE_DIR=../documents/test-report/2026-09-18-01/screenshots`）。
- **通过 = 每用例 1 张关键图**（真实界面截图优先）；**失败 = 完整证据链**（请求/响应/页面状态全程取证）；纯 API 面（产品无 UI）用渲染证据页并逐处注明；UI 暂无列的字段（如 `next_retry_at`）以 API 证据页补位。

## 6. 通过标准（准入/准出）

**准入**：Docker 可用（Testcontainers/共享容器）；m2 依赖齐备（`mvn -o` 离线）；被测实例健康（E2E 前置探测通过）；被测构建新鲜（改 starter 后已 install，IT exec 未过期）。

**准出（全部满足）**：

1. 四层全绿：单元 421/421（另 client-starter 10/10）、冒烟 8/8、IT 136/136、E2E 72/72；
2. 覆盖率双门槛通过：七包 100%（dto/entity/kms/tracelog/client/util/controller） + BUNDLE ≥96%；
3. 无未关闭的 P0/P1/P2 缺陷；P3 允许「登记不修、附修复意见、随下版本处理」；
4. 本轮回归报告与证据按 §5.3 规约归档（通过关键图齐备、失败有完整证据链）；
5. 修复项复验成立：上轮台账修复项在本轮有对应回归用例且全绿（如 v1.2.2 七项修复 → `NfySignatureFaceTest`、L5 零绕行、L8-08 等）。

## 7. 缺陷分级

| 级别 | 定义 | 处置 |
|---|---|---|
| P0 | 阻断性/安全缺陷：出厂默认即不可用或存在安全风险（如 D-1 出厂强制签名瘫 SPA、D-3 wall filter 拦死引擎与迁移） | 必修，阻塞发布；修复后须有防回归用例 |
| P1 | 核心功能缺陷：主链路不可达（如 ND-L5-01 EMAIL 渠道无法使能外发） | 必修，阻塞发布；修复后全量回归 |
| P2 | 功能/兼容缺陷：有绕行或局部影响（如 D-4 redisson 传递引入、F-1 Invalid Date、F-2 失效 token 行为） | 修复后回归；允许与下个迭代合批 |
| P3 | 展示/体验/测试资产改进点（如未读角标「0」不隐藏、订阅页恒选列渲染、投递页缺「下次重试」列） | 登记不修，附修复意见，随下版本处理；不阻塞发布 |

判定补充：环境项（代理 fake-IP DNS 致 IM 域名命中 SSRF 禁用集 10609、共享实例限流 10500/平台公告上限 10611 触发）登记为环境非缺陷；取证伪影（如 el-badge 进场过渡致截图角标不可见）判定为伪影并修正取证手法，不计缺陷。

## 8. 回归纪律

1. **m2 串行**：IT 逐业务线串行执行（`bin/run-it-lines.sh`），防本地 m2 仓库并发竞态；后端构建类子代理同一时刻只允许一个。
2. **构建命令**：mvn 一律显式 `cd backend`；install 输出不过滤不吞（`> log 2>&1; echo RC=$?`），怀疑旧 jar 时 javap 核对字节码。
3. **改 starter 必 install**：`notification4j-it` 从 m2 拉依赖不经 reactor；中间 install 带 `-Djacoco.skip=true`（IT exec 过期拦红属刻意防回退），收尾全量单测补跑 check。
4. **数据隔离**：IT 每个测试用独立 userid/租户；凡「按用户/租户计数或唯一」的约束（渠道同租户同用户同类型 ≤5 上限、biz_no 幂等、email 唯一等）必须防用例间状态污染。
5. **用例独立性**：常规 IT 用例相互独立、禁顺序依赖；唯一例外为冒烟顺序链（理由见 §3）。
6. **测试与产品代码分离**：回归轮次不改产品代码（纯验证轮），缺陷登记修复意见、随修复轮处理；测试资产自身的卫生问题（如 spec 坏字节）单独立项。
7. **口径以实跑为准**：静态 grep `@Test` 计数 ≠ surefire 实跑数（同行注解会漏、TCK 继承用例不在本文件），报告一律引用 surefire/it-summary/playwright 实跑数字。

## 9. 交付物

每轮回归产出：统一测试报告（`docs/test/report/<轮次>/统一测试报告.md`）+ 八线分报告 + 证据截图（`screenshots/`）+ 复跑命令（含 `docker start`、`start-app.sh`、playwright、`run-it-lines.sh` 全序列）；结构见 `docs/templates/test-report-template.md`。
