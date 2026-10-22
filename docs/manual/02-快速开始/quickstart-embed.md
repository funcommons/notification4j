# 快速开始：嵌入你的 Spring Boot 应用（starter · 进程内发消息）

> 适用版本 **v1.4.0**。目标：在你的业务代码里注入 `NotifyClient`，**进程内**发出第一条站内信（无 HTTP、无独立部署）。
> 适用于 Spring Boot 3.2.x（本产品父工程实跑 3.2.7）+ JDK 17 的应用；MyBatis-Plus 3.5.7 等由 starter 传递引入。
> 文中配置键、方法签名均取自仓内源码（`NfyAutoConfiguration`、`NotifyClient`、`NfyDataPlaneTakeoverTest`）。

## 1. 前置

- 一个 Spring Boot 3.2.x / JDK 17 的应用（`spring-boot-starter-web` 项目即可）。
- PostgreSQL 16 + Redis 7：可直接**复用 [quickstart-5min.md](quickstart-5min.md) 起的 `nfy-pg` / `nfy-redis` 容器**
  （或任何可达实例；下面配置示例按该容器的 `localhost:5432` / `6379`、库 `notification4j` 书写）。

## 2. 引依赖（JitPack + starter 坐标）

`pom.xml` 增加仓库与依赖（仓库地址取自仓内 `backend/pom.xml` 的 `jitpack.io` 配置）：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>com.github.funcommons.notification4j</groupId>
    <artifactId>notification4j-starter</artifactId>
    <version>v1.4.0</version>
</dependency>
```

- JitPack 按仓库 tag 解析坐标 `com.github.funcommons.notification4j:notification4j-starter:v1.4.0`（根 README 口径）；
  仓内本地构建时的坐标是 `fun.commons.notification4j:notification4j-starter:1.4.0`（backend/README），两者指向同一构件。
- framework4j v1.7.1 由 starter 传递引入，无需单独声明。
- **JitPack 首次拉取需等待远程构建**（常见 1~3 分钟）；失败时先看构建日志：
  `https://jitpack.io/com/github/funcommons/notification4j/v1.4.0/build.log`。

## 3. 配数据面（只配 framework4j.*，零 spring.datasource、零 exclude）

`application.yml`（键名与 `NfyDataPlaneTakeoverTest` 的 `@DynamicPropertySource`、app 出厂 `application.yml` 一致）：

```yaml
framework4j:
  datasource:
    enabled: true                    # framework4j 多数据源接管（缺省 false，须显式开）
    datasources:
      default:
        url: jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified
        username: admin
        password: test@2026
        druid:
          filters: stat,slf4j        # 出厂默认 filters 含 wall，不兼容 PG 方言（SKIP LOCKED/部分索引），须去掉
  redis:
    enabled: true                    # 同为接管闸门（缺省 false）
    datasources:
      default:
        host: localhost
        port: 6379

nfy:
  runtime:
    client-enabled: true             # NotifyClient 门面开关（缺省 false，不开注入不到 Bean）
    mode: local                      # 缺省即 local：进程内直连 service
```

v1.3.0 起接入方**无需**以下两项（starter 的 `NfyDataPlaneTakeoverFilter` 按 `framework4j.datasource.enabled` /
`framework4j.redis.enabled` 闸门自动 veto 原生装配，验证用例即 `NfyDataPlaneTakeoverTest`）：

- ~~`spring.datasource.*`~~（只配 framework4j 前缀即可；接管生效后原生装配被 veto，补 spring.datasource 也不会被消费——v1.3 之前无 filter 时补配才会同库双池）
- ~~`spring.autoconfigure.exclude`~~（DataSource/Druid/MybatisPlus/TxManager/Redisson 退场由 filter 接管）

雪花 id 也**零配置**：starter 自带 `IdentifierGenerator` 兜底注册（`NfyMybatisPlusSupportAutoConfiguration`），
主键自动生成（`NfyDataPlaneTakeoverTest` 即「不注册任何 id 生成器」裸跑验证）。

启动类加一行 mapper 扫描约定（异基包宿主必需；同基包应用可省）：

```java
@SpringBootApplication
@MapperScan("fun.commons.notification4j.mapper")   // mapper 注册约定（backend/README「嵌入接入形态」）
public class HostApplication { ... }
```

## 4. 建表 + 建类型（一次性）

（1）初始化表结构，二选一：

- 直接执行仓库全量建表脚本：`psql ... -f backend/schema.sql`；
- 或宿主引入 `flyway-core` 并保持默认 `classpath:db/migration` —— Flyway 脚本随 starter jar 提供，首启自动迁移。

（2）插入一条消息类型（发送面会校验类型存在，否则 `10601`；列默认值见 `backend/schema.sql`）：

```sql
INSERT INTO nfya_message_type (id, tenant_id, type_code, name) VALUES (1, 1, 'ORDER_PAID', '订单通知');
```

嵌入形态没有平台域/租户注册流程：宿主自身即一个租户，自用一个固定内部 `tenant_id`（示例用 `1`），
发送与查询保持同值即可（id 非自动生成列，手工插入时需显式给值）。

## 5. 接口面开关速查

键与缺省值逐一取自 `NfyAutoConfiguration.java` 的 `@ConditionalOnProperty` 与 `NfyProperties` 缺省：

| 键 | 缺省 | 作用 |
|---|---|---|
| `nfy.runtime.client-enabled` | `false` | `NotifyClient` 门面装配开关（本教程必开） |
| `nfy.runtime.mode` | `local` | `local`=进程内直连 / `remote`=HTTP 调独立部署实例 |
| `nfy.runtime.enable-api` | `false` | 三域 REST API 控制器（异基包宿主要暴露 HTTP 接口时开 `true`） |
| `nfy.data.enabled` | `true`（缺配即开） | 数据面闸（前提：上下文存在 `SqlSessionFactory`） |
| `nfy.runtime.engine.enabled` | `false` | 站外投递引擎（钉钉/企微/飞书/邮件外发时才开） |
| `nfy.enabled` | `true`（总闸） | `false` 时数据面接管全部放行，宿主恢复原生装配（逃生门） |

异基包宿主暴露 REST 面需要：`nfy.runtime.enable-api=true` + `nfy.data.enabled=true`（否则启动期打 WARN 提示 API 将 404）。

## 6. 发第一条消息（Java）

方法签名与源码一致（`fun.commons.notification4j.client.NotifyClient` / `SendMessageRequest`）：

```java
import fun.commons.notification4j.client.NotifyClient;
import fun.commons.notification4j.client.SendMessageRequest;

@Service
public class OrderService {

    private final NotifyClient notifyClient;

    public OrderService(NotifyClient notifyClient) {
        this.notifyClient = notifyClient;
    }

    public void paySuccess(String userId) {
        NotifyClient.SendMessageResult r = notifyClient.send(
                1L,                                                    // tenantId：与建类型时的 tenant_id 一致
                SendMessageRequest.of(
                        "ORDER_PAID",                                 // typeCode
                        java.util.List.of(userId),                    // userIds
                        "订单已支付",                                  // title
                        "订单 OD-123 已支付成功",                      // content
                        "NORMAL",                                     // level（NORMAL/IMPORTANT/URGENT；null 缺省 NORMAL）
                        null,                                         // linkUrl（可空）
                        "biz-20260929-001"));                         // bizNo（幂等闸；null 服务端生成 UUID）
        // r.messageId() / r.bizNo() / r.receiverCount() / r.inappSaved() / r.deliveryPlanned()
    }
}
```

同接口还有：`unreadCount(tenantId, userid)`、`listMessages(tenantId, userid[, cursor, limit])`、`announce(...)`（快捷公告）。

嵌入形态（`mode=local`）下 starter 装配的是 `LocalNotifyClient`——进程内直连 service 层，无 HTTP 往返；
`ApiException` 统一转 `NfyClientException`（code/消息不变）。

> 只发消息、不想引 MyBatis/PG 数据面？那是跨进程形态：改引 `notification4j-client-starter` 并配
> `mode=remote + remote-url/remote-tenant-id/remote-tenant-secret` 调独立部署实例，业务代码零改动
> （API 同 FQCN）。契约见 [../../api/api-spec.md](../../api/api-spec.md) 与 backend/README「跨进程接入形态」。

## 7. 给用户一个消息中心

站内信已落库，终端用户还需要看得见：消息中心前端（铃铛 + 消息/公告/渠道/订阅/投递五页）以 iframe 嵌入你的页面，
postMessage 握手下发租户 token 即可用——**5 分钟接入步骤见 [quickstart-frontend.md](quickstart-frontend.md)**。

## 8. 常见卡点

| 卡点 | 现象 | 处置 |
|---|---|---|
| 门面未开 / mapper 未扫 | 启动即失败：`local 门面需要数据面：nfy.data.enabled=true 且扫描 fun.commons.notification4j.mapper` | 开 `nfy.runtime.client-enabled=true`；启动类补 `@MapperScan("fun.commons.notification4j.mapper")` |
| 数据面闸没开 | 只配了 `datasources.*` 但 `framework4j.datasource.enabled` 缺省 false，原生装配向 `spring.datasource.*` 要 url 报「Failed to configure a DataSource」 | 显式 `framework4j.datasource.enabled=true`（redis 同理） |
| druid wall 拦 PG | 引擎扫描/Flyway 迁移报 SQL 语法错 | `druid.filters: stat,slf4j`（出厂同款，勿加 wall） |
| 安全面机密缺失 | 启用 `framework4j.access-token` / `framework4j.sensitive` 后，`JWT_SECRET`（≥32B）/ `AES_KEY`（32B）无默认值 fail-fast | 环境变量注入（与独立部署同口径；纯 local 门面最小面无此三项） |
| 发送返回 `10601` | 消息类型不存在或已停用 | 先按第 4 步建类型，且 `type_code` 与库中一致 |
| Boot/JDK 版本 | starter 按 Boot 3.2.x / JDK 17 构建验证 | 宿主保持 3.2.x + JDK 17（父工程实跑 3.2.7） |
| JitPack 拉不动 | 首次构建长时间等待或 404 | 等远程构建完成（看 build.log）；或本地 `cd backend && mvn install -DskipTests` 后改用本地坐标 |

## 下一步

- 消息中心前端嵌入：[quickstart-frontend.md](quickstart-frontend.md)
- 独立部署与 curl 全链路：[quickstart-5min.md](quickstart-5min.md)
- 完整接口契约：[../../api/api-spec.md](../../api/api-spec.md) · 部署手册：[../05-部署与运维/deployment.md](../05-部署与运维/deployment.md)
