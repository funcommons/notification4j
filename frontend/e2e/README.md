# e2e/

V1.0 收官轮已将 benefit/dev 应用源码归档至 `../legacy/src/`，原有 21 个 Playwright e2e
文件（smoke 4 + regression 7 + visual 快照 9 + helpers 1）全部针对已删除的 `/benefit/**`、
`/dev` 路由，保留会必然误导，已于同轮整体随迁至 **`../legacy/e2e/`**（原始路径结构零改动，
零物理删除）。

## V1.2 起重写计划

现有用例均针对已归档 benefit/dev 应用，已随迁 `legacy/e2e/`。
nfy 嵌入应用的 e2e 待 Playwright 环境可用时，基于 `/nfy/tenant/app/**` 路由重写；
在此之前本目录保持为空骨架，不放置任何 spec 代码。

## 现状说明

- `playwright.config.ts` 的 `testDir` 仍指向 `./e2e`（空目录不影响配置加载）。
- `package.json` 的 `test:e2e` script 保持原样，运行时自然 0 用例。
- `vitest.config.ts` 的 `exclude` 已同步加入 `legacy/e2e/**`，防止 vitest 误扫随迁的
  Playwright 用例（误扫必挂，见该文件内注释）。
