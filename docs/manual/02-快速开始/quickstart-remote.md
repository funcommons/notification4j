# 快速开始 · 跨进程轻量接入（client-starter）

> notification4j 使用手册 · 02-快速开始第 3 篇 · 版本口径 **v1.4.0**（Maven `1.4.0` / tag `v1.4.0`）。
> 面向读者：消息中心由一方**独立部署**（fat jar），你的服务**跨进程 HTTP** 调它发消息 / 公告，
> 且不想把数据面（PostgreSQL / MyBatis / Redis / 外发引擎）带进自己进程的业务方后端工程师。
> 配置键与方法签名逐一核对自 `backend/notification4j-client-starter/` v1.4.0 源码；API 契约以 [api-spec](../../api/api-spec.md) 为唯一事实源。

## 1. 这种形态适合你吗（三条全中即本篇，否则见文末「相关路径」）

1. 服务端已**独立部署**且网络可达（`notification4j-app-1.4.0.jar`，出厂端口 9200）；
2. 你只想引一个**零数据面** starter：依赖树仅 spring-boot-autoconfigure + framework4j-transport
   + jackson + spring-web（`backend/notification4j-client-starter/pom.xml`），零 MyBatis / JDBC / Redis；
3. 你用 Java，只需要 `NotifyClient` 门面（发送 / 公告 / 未读 / 列表）。

## 2. 第 1 步：引依赖

JitPack 按 tag 引入（与全量 starter 同 tag，见 [手册 README §4](../README.md)）：

```xml
<dependency>
    <groupId>com.github.funcommons.notification4j</groupId>
    <artifactId>notification4j-client-starter</artifactId>
    <version>v1.4.0</version>
</dependency>
```

> 内部 Maven 仓坐标为 `fun.commons.notification4j:notification4j-client-starter:1.4.0`。
> **互斥契约**：与全量 `notification4j-starter` 二选一，不要同时引。万一同 classpath，
> 全量 starter 的 Bean 确定性优先（client-starter `@ConditionalOnMissingBean` 让位），行为仍正确
> 但启动会打 WARN 提示移除其一（`backend/README.md` 部署节）。

## 3. 第 2 步：配置（必填 / 可选分列）

**装配总开关：`nfy.runtime.client-enabled=true`**——不配（缺省 false）整个 starter 不装配、
不注册任何 Bean。必填三项（出处 `remote/NfyClientProperties.java` + `ClientAutoConfiguration.java`）：

| 键 | 默认值 | 说明 |
|---|---|---|
| `nfy.runtime.client-enabled` | `false` | 必须显式 `true` 才装配 |
| `nfy.runtime.mode` | `remote`（client-starter 口径） | 显式配 `local` 装配期 Assert 失败：本 starter 无数据面，提示改用全量 starter |
| `nfy.runtime.remote-url` | 无 | 服务端基地址，如 `http://notification4j-svc:9200`；缺失**启动即失败** |

可选：

| 键 | 默认值 | 说明 |
|---|---|---|
| `nfy.runtime.remote-tenant-id` | 无 | 本租户 OpenID（独立部署平台「租户管理」获取）。与下行配齐才自动注入 HMAC 签名 |
| `nfy.runtime.remote-tenant-secret` | 无 | 租户 HMAC-SHA256 密钥。两者缺任一 → 跳过签名仅打 WARN，远端会拒 |
| `nfy.runtime.connect-timeout-ms` | `5000` | 仅当装配**自建** RestTemplate 时生效；业务方已有唯一 RestTemplate Bean 则原样复用、不改写其超时 |
| `nfy.runtime.read-timeout-ms` | `10000` | 同上 |

```yaml
nfy:
  runtime:
    client-enabled: true
    mode: remote                                  # 缺省即 remote，显式写出便于与全量 starter 互读
    remote-url: http://notification4j-svc:9200
    remote-tenant-id: t_xxxx                      # 租户 OpenID
    remote-tenant-secret: xxxx                    # HMAC 密钥
    connect-timeout-ms: 5000                      # 可选
    read-timeout-ms: 10000
```

## 4. 第 3 步：注入 NotifyClient，发一条定向消息

门面接口 `fun.commons.notification4j.client.NotifyClient`：

| 方法 | 语义 |
|---|---|
| `send(long tenantId, SendMessageRequest req)` | 发定向消息（站内信 + 订阅矩阵外发计划） |
| `announce(long tenantId, AnnounceRequest req)` | 快捷公告：创建即发布（缺省立即生效 + 7 天失效） |
| `unreadCount(long tenantId, String userid)` | 未读数（站内未读 + 公告未确认合成） |
| `listMessages(long tenantId, String userid, String cursor, Integer limit)` | 我的消息（Cursor 分页）；`(tenantId, userid)` 两参重载默认 `limit=20` |

```java
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.client.SendMessageRequest;

@Service
public class OrderNotifier {

    @Autowired
    private NotifyClient notifyClient;                // 门面接口，直接注入（backend/README 同款写法）

    public void notifyShipped(long tenantId, String userId, String orderNo) {
        NotifyClient.SendMessageResult r = notifyClient.send(tenantId,
                SendMessageRequest.of(
                        "ORDER_SHIPPED",                              // type_code：本租户已启用类型
                        java.util.List.of(userId),                    // user_ids：1~1000 个
                        "订单已发货",                                   // title（1~100）
                        "订单 " + orderNo + " 已发货，点击查看物流。",     // content（1~2000）
                        "NORMAL",                                     // level：NORMAL/IMPORTANT/URGENT，可 null
                        "https://shop.example.com/orders/" + orderNo, // link_url 可 null
                        "biz-" + orderNo));                           // biz_no 幂等业务号 可 null
        // r.messageId() / r.bizNo() / r.receiverCount() / r.inappSaved() / r.deliveryPlanned()
    }
}
```

> `tenantId` 参数在 remote 模式被客户端忽略——租户由请求鉴权上下文决定，同一份代码跨模式零改动。

## 5. 第 4 步：查未读与消息列表

```java
NotifyClient.UnreadSummary u = notifyClient.unreadCount(tenantId, "u_1001");
u.unreadCount(); u.unconfirmedCount(); u.total();     // long：站内未读 / 公告未确认 / 合计

NotifyClient.MessagePage page = notifyClient.listMessages(tenantId, "u_1001");   // 默认 limit=20
page.list();        // List<MessageItemSummary>：messageId()/title()/typeCode()/level()/readStatus()/createdAt()
page.nextCursor(); page.hasMore();  // 下一页游标透传回 listMessages(..., cursor, limit)
```

**T+U 口径注意**：`unread-count` 与消息列表在服务端属 T+U 端点（TENANT token + `X-User-Id` 头，
缺失 → 信封 `10101 X-User-Id 不能为空`）。当前 `RemoteNotifyClient` 不注入 `X-User-Id`
（`userid` 参数同样被忽略），跨进程按用户视角查询需自定义 `HttpTransport` 装饰器补该头
（§6 的让位机制同理）；终端用户视角的查询通常由嵌入的消息中心 SPA 承担
（见 [quickstart-frontend.md](quickstart-frontend.md)）。

任何非 0 信封统一抛 `NfyClientException`（`getCode()` 取业务码，local/remote 同构），如
`10601` = 消息类型不存在或已停用（先到平台建类型，见 [03-开发指南/onboarding.md](../03-开发指南/onboarding.md)）。

## 6. 鉴权头是如何注入的（关键一节）

`AuthenticatedHttpTransport` 装饰器对每个请求自动注入两组头：

1. **HMAC 签名四元组**（`remote-tenant-id`/`remote-tenant-secret` 配齐即生效）：
   `X-Access-Key` / `X-Timestamp` / `X-Nonce` / `X-Signature`（签名串
   `METHOD\nPATH\nTS\nNONCE\nBODY_MD5`）。服务端出厂不强制校验（按租户开关 `privileges.signature`）。
2. **`Authorization: Bearer <JWT>`**：服务端 runtime 面**出厂强制** `@RequiresToken(TENANT)`
   （api-spec §2.4），没有有效 Bearer 会被 102xx 认证信封拒绝。默认装配引了 framework4j-accesstoken
   （类与 `AccessTokenGenerator` Bean 均在）时注入的是 **SERVICE 型** S2S JWT，而独立部署
   runtime 面按 **TENANT 型别**校验（不匹配 → 信封 `10300 令牌类型不匹配`）；未引 accesstoken
   的轻量分支则干脆不带 Bearer（`10200` 直接被拒）——因此推荐用
   **AUTH-001 换来的 TENANT token** 作供源：

```java
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.framework4j.transport.RestTemplateHttpTransport;
import fun.commons.notification4j.remote.AuthenticatedHttpTransport;
import fun.commons.notification4j.remote.NfyClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NfyRemoteTransportConfig {
    private volatile String cachedToken;
    private volatile long expireAtMs;
    /** 业务方自定义 HttpTransport Bean → 装配的 @ConditionalOnMissingBean 让位，改用本定义 */
    @Bean
    public HttpTransport nfyClientHttpTransport(NfyClientProperties props,
                                                com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new AuthenticatedHttpTransport(
                new RestTemplateHttpTransport(new org.springframework.web.client.RestTemplate(factory)),
                tenantId -> tenantToken(props),      // ServiceTokenSupplier：Bearer 来源
                props, mapper);
    }

    /** AUTH-001（api-spec §2.3）：client_credentials 换 TENANT token；默认 8h，到期前 5min 重换并缓存（防 429 锁定：连错 5 次锁 15min） */
    @SuppressWarnings("unchecked")
    private synchronized String tenantToken(NfyClientProperties props) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < expireAtMs - 5 * 60_000L) return cachedToken;
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.getRemoteTenantId());
        form.add("client_secret", props.getRemoteTenantSecret());
        java.util.Map<String, Object> resp = new org.springframework.web.client.RestTemplate().postForObject(
                props.getRemoteUrl() + "/nfy/api/v1/auth/token",
                new org.springframework.http.HttpEntity<>(form, headers), java.util.Map.class);
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) resp.get("data");
        cachedToken = (String) data.get("access_token");
        expireAtMs = now + Long.parseLong(String.valueOf(data.get("expires_in"))) * 1000L; // 字符串秒数
        return cachedToken;
    }
}
```

HMAC 四元组在此定义下仍自动注入（props 已带凭据），租户开启签名时同样满足。

## 7. 不想引 starter？裸 HTTP 等价

先 `POST /nfy/api/v1/auth/token` 换 token，再带 `Authorization: Bearer` 调 `POST /nfy/api/v1/runtime/messages`
——同款 curl 见 [quickstart-5min.md](quickstart-5min.md)（异构语言 Python/Go 也走这条路）。

## 8. local ↔ remote 双模式切换

`NotifyClient` / `SendMessageRequest` / `AnnounceRequest` / `NfyClientException` 四件在两个 starter 中
**同包同名同形态（同 FQCN）**，配置前缀同为 `nfy.runtime`、门面开关同为 `nfy.runtime.client-enabled`：

| 切换方向 | 动作 |
|---|---|
| remote → local（数据落你自己的库） | 换依赖坐标为全量 starter，yml 改 `mode: local` 并配数据面（PG/Redis），业务代码零改动 |
| local → remote | 换回 client-starter（或全量 starter 配 `mode: remote`），yml 指向远端 |

通用模式（mode / enable-api / remote-url / 门面接口 / 让位装配）的完整设计见
[dual-mode-starter-pattern.md](../../governance/dual-mode-starter-pattern.md)（通用版占位符表述，
映射到本项目：前缀 `nfy.runtime`、门面 `NotifyClient`）。

## 9. 常见卡点（均源码 / 实测核实）

| 症状 | 原因 | 处置 |
|---|---|---|
| 启动失败 `nfy.runtime.remote-url 必填（notification4j-client-starter 仅支持 remote 模式）` | 缺 `remote-url` | 补上服务端地址 |
| 启动失败 `mode=local 不受 notification4j-client-starter 支持…` | 配了 `mode: local` | 改 `remote` 或换全量 starter（[quickstart-embed.md](quickstart-embed.md)） |
| 日志 WARN `remote-tenant-id/secret 未配, 跳过签名` | 凭据两键缺任一 | 补齐两键 |
| `NfyClientException` 102xx（如 10202 无效）/ 10300 令牌类型不匹配 | 无 / 坏 Bearer，或用了默认 SERVICE 型 S2S JWT 调 TENANT 端点 | 按 §6 接 AUTH-001 供源 |
| `NfyClientException` 10101 X-User-Id 不能为空 | T+U 端点缺 `X-User-Id` 头 | 见 §5 口径注意 |
| `NfyClientException` 10601 | type_code 未建 / 已停用 | 先建消息类型（[onboarding](../03-开发指南/onboarding.md)） |
| 连接 / 读超时 | 网络不通或超时过短 | 核对 `remote-url` 可达性；超时键仅对自建 RestTemplate 生效 |
| 启动 WARN 两个 starter 共存 | 同 classpath 引了两个 starter | 按互斥契约移除其一 |

## 10. 相关路径

- [quickstart-5min.md](quickstart-5min.md)（裸 HTTP 冒烟）、[quickstart-embed.md](quickstart-embed.md)（同进程嵌入）、
  [onboarding.md](../03-开发指南/onboarding.md) / [auth.md](../03-开发指南/auth.md)（凭据与类型开通）、[sending.md](../03-开发指南/sending.md)（发送全链）、[error-codes.md](../04-参考/error-codes.md)（错误码速查）
