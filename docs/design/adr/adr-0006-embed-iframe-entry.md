# ADR-0006: 嵌入形态=iframe 四入口 + postMessage 握手

## 状态
proposed（2026-09-16，评审第 1 轮修订：补 READY 重发与刷新重握手）

## 上下文
接入方应用需 0.5 天嵌入消息中心（铃铛/列表/公告/渠道/订阅）。宿主技术栈异构（Vue/React/Angular/老 jQuery 均可能）。

## 备选方案
- A. npm 组件包/Web Component：强耦合宿主技术栈与发版节奏；鉴权（token 传递）与白标（主题下发）复杂
- B. iframe 四入口（app/page × platform/tenant）+ postMessage 握手（《租户设计》§7 既定方案，benefit4j 已生产验证）

## 决策
选 B。握手健壮性（评审第 1 轮补强）：iframe 周期 500ms 重发 `NFY_READY` 直至收到首个 `NFY_TOKEN`（防父页监听未就绪丢 READY 死锁）；iframe 刷新 token 丢失时重新发起 READY 流程；父页按 expires_in 计时主动续发。推荐级加固：租户后端代换 token 时绑定 user_id（claim 携带），服务端校验与 X-User-Id 一致。

## 后果
正面：宿主零依赖、白标/认证复用 benefit4j 全套；负面：iframe 固有体验限制（弹层溢出、路由不同步——以 page/app 双壳缓解）；READY 重发产生少量无害消息。
