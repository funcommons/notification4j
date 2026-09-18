# 交付清单（Delivery Checklist）

> 基线：v1.2.2（commit `a91f7a9`，2026-09-18）。事实源：`docs/release/delivery-summary.md`（V1.0 交付总结）、
> `docs/test/report/2026-09-18-01/统一测试报告.md`（第 2 轮回归）、仓库树实况。
> 标注口径：✅ 已齐 / ⏳ 待补（附事实原因）/ ※ 约定存在（由并行治理轮产出，路径已约定）。

## 一、代码

| 资产 | 状态 | 说明 |
|---|---|---|
| `backend/notification-spring-boot-starter` | ✅ | 业务方唯一依赖：三域 API / 服务 / 外发引擎 / NotifyClient 门面 |
| `backend/notification4j-app` | ✅ | 独立部署 fat jar（Flyway + 出厂 yml + 前端入 jar） |
| `backend/notification4j-client-starter` | ✅ | 跨进程形态（remote HTTP，零 MyBatis/JDBC/Redis，单测 10/10） |
| `backend/notification4j-it` | ✅ | 集成回归（Testcontainers PG16+Redis7，129 用例） |
| `backend/schema.sql` / `legacy-reference/` / `bin/` | ✅ | 参考/辅助资产 |
| `frontend/`（Vue3 消息中心五页 + 铃铛） | ✅ | postMessage 嵌入握手 / 未读轮询 / 投递重投 UI |
| `frontend/e2e-regression/` | ✅ | 8 业务线 spec（l1~l8）+ global-setup/helpers/hosts + bin（start-app / run-it-lines） |
| `frontend/legacy/` | ✅（归档） | benefit 遗留零引用归档；物理删除时机待决策（见边界 B-10） |
| Maven / npm 版本坐标 | ⏳ | git tag 已至 v1.2.2，`backend/pom.xml` 与前端 `package.json` 仍为 1.0.0（下个交付窗口统一 bump） |

## 二、测试

| 门 | 基线（v1.2.2） | 状态 |
|---|---|---|
| 后端单元（starter，mock 切片） | **411/411** + 双 JaCoCo 门槛（七包 100% 逐包卡死 + BUNDLE ≥96%） | ✅ |
| 后端冒烟（NfySmokeTest 有序链） | 8/8（含于 IT） | ✅ |
| 后端集成回归 IT | **129/129**（Testcontainers PG16+Redis7） | ✅ |
| 前端三门（vue-tsc / eslint / vitest） | 0 错 0 警 / vitest **382** | ✅ |
| E2E（Playwright 出厂等价实例） | **70/70**（八业务线） | ✅ |
| 缺陷台账 | P0×3 + P1 + P2×3 全部修复并复验（第 2 轮回归）；在册 P3×3 + 测试资产卫生 1 项（见边界） | ✅ |

复跑命令见 `docs/test/report/2026-09-18-01/统一测试报告.md` §六 与根 `CONTRIBUTING.md` 测试门禁节。

## 三、文档（docs/ 树逐目录）

| 路径 | 状态 | 内容 |
|---|---|---|
| `docs/README.md` | ※ | 文档树索引（并行治理轮产出） |
| `docs/api/api-spec.md` | ✅ | 接口设计文档，修订史 V1.0.0~V1.2.2 完整，附录 A 50/50 实现矩阵 |
| `docs/requirements/prd.md` | ✅ | 产品设计文档 |
| `docs/design/system-design.md`、`database-design.md` | ✅ | 系统/数据库设计 |
| `docs/design/adr/adr-0001~0011` | ✅ | 11 条架构决策记录（含 V1.2 补录的 ADR-0010/0011，状态列 accepted） |
| `docs/governance/dev-principles.md` | ✅ | 开发原则（强制约束） |
| `docs/governance/dual-mode-starter-pattern.md`、`middleware-tenant-design.md` | ✅ | 双模式方案 / 租户设计 |
| `docs/operations/integration-guide.md` | ✅ | 嵌入集成指南（终审 4 处修正已回写） |
| `docs/operations/user-guide.md` | ※ | 使用指南（并行治理轮产出） |
| `docs/deployment/deployment-guide.md` | ※ | 部署指南（并行治理轮产出） |
| `docs/release/delivery-summary.md` | ✅ | V1.0 交付总结（第 1~30 步全程） |
| `docs/release/release-notes.md`、`delivery-checklist.md`、`acceptance-report.md` | ✅ | 本轮治理新增：发布说明 / 交付清单 / 验收报告 |
| `docs/templates/adr-template.md`、`test-report-template.md` | ※ | 文档模板（并行治理轮产出） |
| `docs/test/report/2026-09-17-01/` | ✅ | 第 1 轮回归报告（统一 + 八线，9 文件） |
| `docs/test/report/2026-09-18-01/` | ✅ | 第 2 轮回归报告（统一 + 八线，9 文件，证据 105 张） |
| 根 `README.md` / `CHANGELOG.md` / `CONTRIBUTING.md` / `SECURITY.md` / `LICENSE` | ✅ | CHANGELOG / CONTRIBUTING / SECURITY 为本轮治理新增；README 快速开始与 backend/README 部署节已按交付态修订 |

## 四、部署产物

| 产物 | 状态 | 说明 |
|---|---|---|
| `notification4j-app` fat jar | ✅ | 前端 dist 精确拷入 `classpath:/nfy-console/`（`nfy.static-page.enabled=true` 出厂开启，免双部署）；repackage 后原 thin jar 存为 `.jar.original` |
| 部署三形态 | ✅ | 独立部署（fat jar）/ 嵌入（starter）/ 跨进程（client-starter），接入决策树见 `docs/release/delivery-summary.md` §八 |
| 出厂配置 | ✅ | 三域 API + 引擎双开；druid `stat,slf4j`；签名面 `path-patterns=[]`（S2S 自行加回，见 SECURITY.md） |
| 机密注入 | ✅ | `JWT_SECRET` / `AES_KEY` / `PLATFORM_CLIENT_SECRET` 环境变量 fail-fast（无默认值不入库不入 git） |
| 前端产物再生成 | ✅ | 流程见 `backend/README.md`「前端产物再生成」节（node_modules 永不入 jar） |
| 运行时依赖 | ✅ | PostgreSQL 16 + Redis 7（Testcontainers 同版本） |

## 五、已知边界（登记不阻塞，逐条可追溯）

| # | 边界 | 状态/去向 |
|---|---|---|
| B-1 | **SMS 渠道**（ADR-0004 引 Sms4J） | ⏳ 待有网/私服环境落地（离线 m2 无依赖，V1.2 唯一剩余项） |
| B-2 | P3×3 展示项：未读角标 0 不隐藏 / 订阅页「站内信」恒选列渲染 / 投递页无「下次重试」列 | 在册（第 2 轮回归 §四），随下个前端版本处理 |
| B-3 | 测试资产卫生：`l1-auth.spec.ts` 6 处 U+FFFD 坏字节 | 在册，下轮修 spec 字面量（不改用例语义） |
| B-4 | 环境复验项：reset-secret 24h 宽限口径对齐任务书 / fake-IP DNS→IM 注册 10609（环境项） | 第 2 轮回归复现一致，非缺陷 |
| B-5 | V1.0 已登记低优：MSG-004 keyword 前端本地过滤、§5.3 Redis 水位线缓存、注册码 Redis 扣减优化、批量 Job biz_no 批次后缀、渠道 ≤5 软限并发窗口、10611 并发软限、STAT 深翻页性能、PAN stats 端点缺失、直发模板渲染边界 | 接口文档修订史在册 |
| B-6 | V1.2 遗留（P2~P3）：静默窗按服务端默认时区（单时区部署边界）、撤回 IT 未启用真引擎、已撤回 biz_no 重发仍 10401、投递页时间窗筛选 UI 未加、投递页无权限开关、SUSPEND 签名面仅代码级保证 | `docs/release/delivery-summary.md` §六在册 |
| B-7 | legacy e2e（benefit 遗留 21 spec） | 已归档 `frontend/legacy/`（vitest exclude 防误扫）；现行 E2E 为 `frontend/e2e-regression/`（按 `/nfy/tenant/app/**` 重写完成） |
| B-8 | Maven/前端版本坐标 bump | ⏳ 下个交付窗口统一（当前 1.0.0 vs tag v1.2.2） |
| B-9 | 开发期库孤儿数据：平台公告确认回执口径修正前的历史行 | 不做迁移登记（`delivery-summary.md` §7.5） |
| B-10 | `frontend/legacy/` 物理删除时机 | 待用户决策 |
