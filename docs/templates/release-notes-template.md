# Release Notes 模板

> 存放：`docs/release/release-notes-v<版本>.md`。验收数据一律引用实跑口径（surefire / it-summary / playwright / jacoco 报告），禁止估算。

```markdown
# notification4j v<X.Y.Z> Release Notes

> 发布日期：<YYYY-MM-DD> ｜ 基线 commit：<sha> ｜ 相对上一版本：<vX.Y.Z>

## 一、版本定位（一句话）

<面向谁的什么版本：如「V1.0 全量交付：站内消息/公告/站外通知多租户微中台，嵌入形态 starter + SPA」>

## 二、亮点（Highlights）

- **<能力 1>**：<一段话：做了什么、关键语义（引用 ADR/契约章节）、用户可感知的价值>
- **<能力 2>**：…

## 三、新增能力清单

| 域 | 能力 | 契约/设计锚点 |
|---|---|---|
| 站内消息 | <发送/批量/已读/撤回…> | API-MSG-00x / ADR-00xx |
| 公告 | … | … |
| 渠道与订阅 | … | … |
| 外发引擎 | … | ADR-0003 |
| 平台治理 | … | … |
| 前端消息中心 | … | ADR-0006 |

## 四、修复清单

| # | 级别 | 问题 | 根因 | 修复 | 防回归 |
|---|---|---|---|---|---|
| D-1 | P0 | <一句话现象> | <真因> | <修法> | <用例：如 NfySignatureFaceTest / L8-08> |
| ND-L5-01 | P1 | … | … | … | L5-00 |

（含 P3 展示类改进点时注明「随本版处理/维持登记」。）

## 五、验收数据

| 层 | 结果 | 口径来源 |
|---|---|---|
| 单元层 | <n>/<n> | starter surefire（411）+ client-starter（10） |
| 冒烟层 | 8/8 | `-Dgroups=smoke`（NfySmokeTest 顺序链） |
| IT 集成回归 | <n>/129 | it-summary：L1 23 · L2 21 · L3 10 · L4 21 · L5 15 · L6 17 · L7 9 · L8 13 |
| E2E | <n>/70 | playwright：L1 8 · L2 6 · L3 6 · L4 13 · L5 7 · L6 10 · L7 12 · L8 8 |
| 覆盖率 | 七包 100% ｜ BUNDLE <实测%>（门槛 ≥96%） | jacoco-merged 报告 |
| 回归报告 | docs/test/report/<轮次>/ | 双层验证 + 证据截图 |

## 六、升级注意（Upgrade Notes）

- **破坏性变更**：<无 / 逐条：契约、配置键、默认值变化>
- **出厂默认面**：<签名 path-patterns、druid filters、autoconfigure exclude 等安全面出厂值及其开启方式>
- **依赖要求**：JDK/PG/Redis/Docker（Testcontainers）版本；框架（framework4j）版本兼容声明（如 redisson 传递引入在 ≥1.7 需复验）
- **升级步骤**：<改 starter 后 install → app 重新 package → Flyway 迁移行为；嵌入方 yml 最小配置示例>
- **已知问题**：<登记未修的 P3 与环境项（fake-IP DNS/代理、共享实例限流），引用测试报告 §四/§五>
```
