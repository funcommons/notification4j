# 贡献指南（Contributing）

感谢关注 notification4j。本文是向本项目提交代码/文档/测试的约束与流程说明；
**强制约束**以 [docs/governance/dev-principles.md](docs/governance/dev-principles.md)（开发原则）为准，本文是其工程落地版。冲突时先停下对齐，不得静默绕过。

## 开发环境

| 项 | 要求 |
|---|---|
| JDK | 17 |
| 构建工具 | Maven（依赖已镜像至本地 m2 仓库，**离线构建**：一律带 `-o`） |
| 容器 | 本地 Docker（集成测试用 Testcontainers PG16 + Redis7） |
| 前端 | Node.js + npm（Vue3 + Vite + vitest + Playwright） |

### 构建纪律（实测踩坑沉淀，违反必返工）

- 后端命令一律**显式 `cd backend`**，前端命令显式 `cd frontend`；
- `mvn install` 输出**不得重定向吞掉**，失败要看到第一现场；
- **改 starter main 代码后必先 `install` 再跑 it 模块**（it 依赖本地仓库里的 starter 产物，否则跑的是旧包）；
- IT 编写注意防用例污染（测试数据按租户/前缀隔离，勿依赖执行顺序的残留状态）。

## 分支与提交规范

- 分支：从 `main` 拉功能分支，命名 `feat/<主题>` 或 `fix/<主题>`；
- 提交信息：`type: 中文一句话摘要`，正文列要点。现有历史风格示例：
  - `feat: notification4j V1.0 全量交付 —— 站内消息/公告/站外通知多租户微中台`
  - `fix: 验收回归修复轮 —— P0×3+P1+P2×3 全部修复，缺陷台账清零`
  - 正文按 P0/P1/P2 分级列缺陷与修复、附验收数据（单测/IT/E2E 数字），可追溯；
- 缺陷分级口径（同开发原则 §5.2）：P0 正确性/资损/安全；P1 一期演进必做；P2 不阻塞但应记录；P3 展示类/低优，登记不阻塞。

## 开发流程（TDD + 评审循环）

遵循开发原则 §5（大任务分步交付）。预估代码量超过 1000 行必须拆步，每步 ≤1000 行，按可独立对齐的最小闭环拆分。每步固定流程：

```text
① 写测试代码（测试向量先行，// VECTOR: 锚点）
② 写业务代码（实现到测试通过）
③ 跑测试（单测 + 集成，门禁见下节）
④ 修复（直到全绿）
⑤ 评审循环：评审 → 出 P0~P2 建议 → 执行 → 修复 → 再评审
   退出条件：连续评审无 P0~P2 建议，或满 4 轮（遗留项记录并升级人为决策）
```

动手前必读开发原则 §1~§4：后端一律用 framework4j（禁重复建设，缺口走 §1.2 两条出路并留 ADR）；先查可复用源；任务 Done = 文档/代码/测试/规范**四方对齐**；任务开始前先扫描加载相关 skills（mc-java-spec / fwk4j-* / mc-doc-* 等）。

## 测试门禁（提交前必须全绿）

### 后端四层

| 层 | 规模基线 | 命令 |
|---|---|---|
| L1 单元（starter，mock 切片） | **411** 全绿 + 双 JaCoCo 门槛 | `cd backend && mvn -o test -pl notification-spring-boot-starter` |
| L2 冒烟（全链路有序链） | 8 用例（含于 L3） | `cd backend && mvn -o test -pl notification4j-it -Dtest=NfySmokeTest` |
| L3 回归 IT（Testcontainers PG16+Redis7） | **129** 全绿 | `cd backend && mvn -o install -pl notification-spring-boot-starter -DskipTests && mvn -o test -pl notification4j-it` |
| L4 覆盖率双门槛 | 七包合并口径行覆盖恰 100% 逐包卡死（dto/entity/kms/tracelog/client/util/controller）+ BUNDLE ≥96% 防回退 | 随 L1 命令尾部执行 |

连跑顺序 L3 → L1（starter 合并口径消费 it 的 `jacoco-it.exec`）。

### 前端三门 + E2E

```bash
cd frontend
npm run verify          # 三门一次过：vue-tsc --noEmit 0 错 + eslint 0 警 + vitest run（基线 382）
# E2E 回归（需 Docker：nfy4j-e2e-pg / nfy4j-e2e-redis）
docker start nfy4j-e2e-pg nfy4j-e2e-redis
bash frontend/e2e-regression/bin/start-app.sh        # 拉起出厂等价实例 :9200
npx playwright test -c e2e-regression/playwright.config.ts   # 基线 70 用例
```

## 文档约定

- **改代码必同步 `docs/` 对应文档**（四方对齐，开发原则 §3）：接口变更同步 [docs/api/api-spec.md](docs/api/api-spec.md) 并在 §1.2 修订历史追加一行；设计变更同步 docs/design/ 对应文档；
- 架构/技术决策用 ADR，模板：`docs/templates/adr-template.md`，落 `docs/design/adr/`（现有 ADR-0001~0011）；
- 测试报告用模板：`docs/templates/test-report-template.md`，落 `docs/test/report/YYYY-MM-DD-NN/`（统一报告 + 分线报告 + 证据截图）；
- 发布类文档落 `docs/release/`。

## PR / 验收标准

PR 合入前逐项自查（对应开发原则 §3 任务关闭清单）：

1. 文档已更新（或确认无需更新）并追加修订历史；
2. 代码与文档描述一致，无 framework4j 重复建设红线；
3. 测试全绿：后端 411 单测 + 129 IT + 双覆盖率门槛；前端三门 0 错 0 警 + E2E 70（触及前端/端到端行为时）；
4. 规范 P0 必查项通过（对应 skill 红线 grep）；
5. 涉及 framework4j 缺口的，已走两条出路之一并留痕（issue / ADR）；
6. 缺陷修复附分级（P0~P3）与复验证据；评审遗留建议不得静默丢弃。

## License

提交即同意以 [MIT License](LICENSE)（Copyright (c) 2026 funcommons）开源贡献你的代码。
