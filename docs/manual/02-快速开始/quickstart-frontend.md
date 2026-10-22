# 快速开始 · 前端嵌入消息中心（五页 + 铃铛）

> notification4j 使用手册 · 02-快速开始第 4 篇 · 版本口径 **v1.4.0**。
> 面向读者：要把消息中心 SPA（消息 / 公告 / 渠道 / 订阅 / 投递五页 + 铃铛）以 iframe 嵌进
> 宿主应用的前端工程师。协议常量与行为核对自 `frontend/src/composables/nfy/useEmbedHandshake.ts`、
> `frontend/src/api/nfy/nfyContext.ts`、`frontend/src/router/index.ts`、E2E 宿主页
> `frontend/e2e-regression/hosts/nfy-host.html` 与 [前端嵌入详解](../03-开发指南/frontend-embed.md)。

## 1. 前置条件与入口路由

前置两件事：

1. **nfy 服务端已独立部署**（`notification4j-app-1.4.0.jar`，`nfy.static-page.enabled=true` 出厂开启，
   SPA 由 jar 直接托管，无需单独部署前端）；
2. 已有**宿主页**（你的应用任意页面），能为当前登录用户向后端换取一个 nfy token（§2）。

SPA 托管了六个入口（路由核对自 `frontend/src/router/index.ts` 与
`backend/.../autoconfigure/NfyStaticPageConfig.java`，与 NfyStaticPageConfig 源码一致）：

| 路由 | 用途 |
|---|---|
| `/nfy/tenant/app/messages` | 消息列表（未读筛选 / 标已读 / 加载更多） |
| `/nfy/tenant/app/announcements` | 公告（需确认的「我知道了」） |
| `/nfy/tenant/app/channels` | 我的渠道（注册 / 验证 / 启停 / 删除） |
| `/nfy/tenant/app/subscriptions` | 订阅矩阵（类型 × 渠道开关） |
| `/nfy/tenant/app/deliveries` | 投递记录（筛选 / 分页，DEAD 行可重投） |
| `/nfy/tenant/page/bell` | 铃铛单页（48×48 起 iframe，角标 + 最近 5 条） |

> 深链（如直开 / 刷新 `/nfy/tenant/app/announcements`）由服务端 SPA fallback 托管
> （`NfyStaticPageController` forward 到入口 index.html），无需宿主处理。

## 2. 第 1 步：最小宿主页（完整可复制）

握手协议（常量与字段名是线契约，勿改）：iframe 加载后**每 500ms 重发**
`{type:'NFY_READY'}` 直到收到 token；宿主回投 `{type:'NFY_TOKEN', token, user_id}`
（字段名 `user_id` 为 snake_case）——token 与 user_id 只进 iframe 内存，不落 URL / storage。

```html
<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>宿主应用（嵌消息中心）</title>
<style>#nfy{width:100%;height:calc(100vh - 56px);border:0}</style>
</head>
<body>
<header>宿主应用顶栏</header>
<!-- 完整五页入口；只嵌铃铛则 src 换 /nfy/tenant/page/bell 并缩小 iframe（§5） -->
<iframe id="nfy" src="https://nfy.example.com/nfy/tenant/app/messages"></iframe>
<script>
(function () {
  var NFY_ORIGIN = 'https://nfy.example.com';   // nfy 服务端 origin（= iframe src 的 origin）
  function currentUserId() { return window.MY_SESSION.userId; }   // 换成你的会话取值

  window.addEventListener('message', function (e) {
    if (e.origin !== NFY_ORIGIN) return;        // ① 只信任来自 nfy 服务端 iframe 的消息

    if (e.data && e.data.type === 'NFY_READY') {
      // ② iframe 就绪（或 token 失效后重新等待）：向宿主后端要 token 再投递。
      //    token 由宿主后端代换（AUTH-001），client_secret 永不出后端，勿硬编码前端
      fetch('/api/nfy-token?user=' + encodeURIComponent(currentUserId()))
        .then(function (r) { return r.json(); })
        .then(function (d) {
          // ③ targetOrigin 显式写服务端 origin，不要用 '*'
          e.source.postMessage(
            { type: 'NFY_TOKEN', token: d.token, user_id: currentUserId() },
            NFY_ORIGIN);
        });
    } else if (e.data && e.data.type === 'NFY_URGENT') {
      // ④ URGENT 未读首次出现通知（30s 轮询口径）：宿主自行决定是否强提醒
      console.log('[nfy] urgent total=' + e.data.total);
    } else if (e.data && e.data.type === 'NFY_NAVIGATE') {
      // ⑤ 铃铛「查看全部」跳转请求：宿主自行打开完整消息中心
      location.href = e.data.url;
    }
  });
})();
</script>
</body>
</html>
```

**重握手（replay）语义**：每次收到 `NFY_READY` 都重新代换并回投，是最简且自愈的写法——
token 失效时 iframe 数据面收到 102xx → 回到 waiting 周期重发 `NFY_READY`（500ms，
`useEmbedHandshake` 缺省），宿主再次应答即恢复。E2E 宿主 `nfy-host.html` 用 `sent` 标记 +
`?replay=1` 参数模拟「只发一次 / 允许重发」两种宿主行为；不重发的宿主会让 iframe 停在
「会话已失效，正在重新连接…」占位（L8-08 实测）。

**token 代换（宿主后端，AUTH-001）**：

```http
POST https://nfy.example.com/nfy/api/v1/auth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id={租户OpenID}&client_secret={租户密钥}
```

返回 `data.access_token`（TENANT 型 JWT，默认 8h；`expires_in` 为字符串秒数）。
V1.0 口径 `X-User-Id` 透传不鉴权；需要按用户短会话 / claim 绑定加固属推荐级，接入方按需启用。

## 3. 第 2 步：配 origin 白名单（双面 ∪，fail-closed）

`NFY_TOKEN` 只接受白名单内 origin 的投递，两面任一命中即接受；**都不命中一律静默忽略**
（拉取失败也忽略，fail-closed）。

**面 1 · 构建时注入**（打包进 SPA，改动需重打包；格式 = 逗号分隔多个 origin）：

```bash
# frontend 构建环境变量（读取处：NfyShell.vue / BellPage.vue 的 import.meta.env）
VITE_NFY_PARENT_ORIGINS=https://app.example.com,https://admin.example.com
VITE_NFY_BASE_URL=/nfy/api/v1
```

缺省回落开发默认 `http://localhost:5173,http://localhost:3000`；也可在组件级用 props
（`<BellPage origins="https://app.example.com" />`，仅组件直用时生效，iframe 托管形态用不到）。
注意：随仓发布的产物按「构建期零 `VITE_*` env」打出（commit 697fdf7），生产宿主 origin
实际依赖面 2。

**面 2 · 运行时租户 `oem.hosts`**（运营时可配、无需重打包）：

```bash
# 平台域配置（平台 token；新增 host 属安全变更，走租户审批）
curl -X PATCH https://nfy.example.com/nfy/platform/api/v1/tenants/{open_id} \
  -H "Authorization: Bearer <PLATFORM token>" -H "Content-Type: application/json" \
  -d '{"oem":{"hosts":["https://partner.example.com"]}}'
```

握手期自动核验：构建时名单外的父页 origin 投递 `NFY_TOKEN` 时，iframe 持该候选 token 调
`GET /nfy/api/v1/runtime/oem/hosts`（API-OEM-001，T 鉴权）拉本租户 hosts，命中才接受
（同 token 在途去重；L8-09 命中 / L8-10 未命中拒绝，均有 E2E 钉死）。

匹配规则：origin **精确串匹配**（scheme+host+port，以浏览器 `event.origin` 序列化为准，
`https://a.com` ≠ `https://a.com:443`）。

## 4. 第 3 步：选嵌入拓扑

### 拓扑 A · 跨源 iframe（默认）

iframe `src` 指向服务端绝对地址（§2 示例即此形态）。SPA 的 API 基址缺省 `/nfy/api/v1`
为相对路径，落在服务端同源，无跨域问题；宿主与 iframe 之间只走 postMessage。

### 拓扑 B · 宿主域同源反代

宿主把 `/nfy/` 前缀**原样透传（不 rewrite）**到服务端，iframe 用同源路径：

```nginx
location /nfy/ {
    proxy_pass http://nfy-svc.internal:9200;   # 不带 URI 部分 → /nfy/... 原样转发
}
```

```html
<iframe id="nfy" src="/nfy/tenant/app/messages"></iframe>
```

行为口径（commit 697fdf7 实测钉死）：

- 前端 vite `base='/nfy/'`：产物资产引用为 `/nfy/assets/*` 等 base 前缀路径，服务端
  `NfyStaticPageConfig` **双轨承接**（`/nfy/assets|images|oem/**` 资源映射与
  `/nfy/favicon.svg`、`/nfy/logo.svg` 转发），与根前缀映射并存——独立部署（:9200 根路径）
  旧引用不受影响；反代**必须原样透传不 rewrite**，否则资产 404、iframe 白屏；
- OEM logo 同理走 base 前缀（`logoUrl = BASE_URL + 'logo.svg'` → `/nfy/logo.svg`）；
- 白名单照常生效：同源反代下 `event.origin` = **宿主域**，须把宿主域加进面 1 或面 2 名单。

## 5. 第 4 步：嵌铃铛

```html
<!-- 本体 48×48；点击展开「最近消息」下拉面板（宽 300px，绝对定位），为面板预留空间或放大 iframe -->
<iframe src="https://nfy.example.com/nfy/tenant/page/bell"
        style="width:48px;height:48px;border:0"></iframe>
```

铃铛行为（核对自 `BellPage.vue` + `useUnreadCount.ts`，E2E L8-04/05 钉死）：

- 未读角标 = `GET /runtime/messages/unread-count` 的 `total`（站内未读 + 公告未确认），
  **30s 轮询**（页面不可见暂停，恢复可见立即补拉；请求失败静默保留旧值）；
- 角标 **99+ 封顶**；点击展开最近 **5 条**（`limit=5`），未读行加粗；
- `has_urgent` 由假转真时向父页发 `{type:'NFY_URGENT', total}`（30s 轮询口径，一次到达只通知一次）；
- 「查看全部」经 `{type:'NFY_NAVIGATE', url}` 请父页跳转（`url` 来自 BellPage 的 `jumpUrl`
  props，宿主监听后自行打开完整消息中心）。注意：jar 托管的 `/nfy/tenant/page/bell` 路由
  未透传该 props，**iframe 托管形态当前不会发出此消息**（`jumpUrl` 仅组件直用时可设），
  §2 ⑤ 的监听属组件直用 / 后续版本的预留。

## 6. 验证清单（与 E2E L8 线对齐）

| # | 检查项 | 预期 |
|---|---|---|
| 1 | 握手成功 | 宿主收到 `NFY_READY` → 回投 `NFY_TOKEN` 后，iframe 出现侧栏品牌「消息中心」（connected 才渲染侧栏） |
| 2 | 消息渲染 | 种子消息标题出现在消息页；URGENT 行带等级样式 |
| 3 | 未读角标 | 铃铛角标数字 = unread-count 接口 `total`；标已读后角标递减 |
| 4 | origin 白名单 | 非白名单父页投 `NFY_TOKEN` 不被接受：iframe 停留「正在连接…」、无侧栏（fail-closed） |
| 5 | 会话失效重握手 | 失效 token：iframe 显示「会话已失效，正在重新连接…」并重发 `NFY_READY`；宿主重投后自愈；不出现误导性「暂无消息」空态 |
| 6 | token 不落地 | token/user_id 仅经 postMessage 内存传递（URL、sessionStorage、localStorage 均无） |

## 7. 下一步

postMessage 握手细节、token 生命周期、白名单运维与安全清单，见
[03-开发指南/frontend-embed.md](../03-开发指南/frontend-embed.md)；
嵌入面鉴权口径（TENANT token + X-User-Id）见 [03-开发指南/auth.md](../03-开发指南/auth.md)。
