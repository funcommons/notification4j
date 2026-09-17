# notification4j backend

多租户通知中间件（站内信 / 公告 / 渠道订阅 / 外发引擎）。模块：

- `notification-spring-boot-starter` —— 业务实现（单测 409）
- `notification4j-client-starter` —— 业务方轻量接入（跨进程 remote，零数据面，第 30 步）
- `notification4j-it` —— 集成测试层（PG+Redis Testcontainers，���库 Flyway 迁移）
- `notification4j-app` —— 独立部署壳（Flyway baseline / OpenAPI）

所有命令在 `backend/` 目录下执行；`-o` 离线模式（依赖已在本机 m2）。

## 测试分层（四层体系）

| 层 | 内容 | 命令 |
|---|---|---|
| 单元层 | starter 纯单测（Mockito/独立上下文，无容器） | `mvn -o test -pl notification-spring-boot-starter` |
| 冒烟层 | 一条顺序链 8 用例跑通核心业务闭环（`@Tag("smoke")`，冷启动 ≤90s） | `mvn -o test -pl notification4j-it -Dgroups=smoke` |
| 集成层（回归层） | it 模块全量 = 24 个常规 IT 套件（用例相互独立）+ 冒烟套件 | `mvn -o test -pl notification4j-it` |
| 全部连跑 | 单测 + 集成一条命令两模块（含覆盖率口径合并与 check 门槛） | `mvn -o clean test` |

说明：

- `-Dgroups=smoke` 只命中 `NfySmokeTest`（全仓唯一 `@Tag("smoke")`），其余套件不受影响；不带该参数即为回归层全量。
- 冒烟套件（`NfySmokeTest`）是刻意设计的顺序链（`@TestMethodOrder(OrderAnnotation)`）：链上 8 步是
  一条业务事务，前步产物即后步输入——与常规 IT「用例独立、禁顺序依赖」纪律的差异及理由见该类 javadoc。
- 根目录 `mvn -o clean test` 的 reactor 顺序为 starter → client-starter → it → app（拓扑序）。

## 覆盖率口径与防回退门槛

- 口径：**单测 exec（starter）+ IT exec（`notification4j-it/target/jacoco-it.exec`）合并**——MockMvc
  全链路覆盖的 controller / autoconfigure / engine 调度线程计入。报告：
  - 单测口径 `notification-spring-boot-starter/target/site/jacoco/`
  - 合并口径 `notification-spring-boot-starter/target/site/jacoco-merged/`（check 即基于此）
- 门槛（绑定 starter build 的 test 阶段，`jacoco:check`）：
  - 门槛一：`dto / entity / kms / tracelog / client / util` 六包行覆盖 **100%**
    （client、util 内两个纯防御 catch 类 `RemoteNotifyClient`、`WebhookSigner` 按类精确排除，仍受门槛二兜底）；
  - 门槛二：BUNDLE 行覆盖 ≥ **96%**（合并口径实测 96.14%）。
- 新鲜度：合并取「最近一次 IT 运行」的 exec。改了 starter 代码后请先跑一次集成层（或先 `mvn -o install
  -pl notification-spring-boot-starter -DskipTests -Djacoco.skip=true` 再跑 IT），否则 starter 单独构建时
  合并的是旧 exec，check 可能因口径过期而失败（这是刻意的防回退语义：提示重新跑 IT）。

## 部署（编码第 29~30 步：部署形态三件套）

### 独立部署形态（notification4j-app fat jar）

```bash
# 构建（repackage 后 notification4j-app-1.0.0.jar 为可执行 fat jar，原 thin jar 存为 .jar.original）
cd backend && mvn -o package -pl notification4j-app -DskipTests

# 运行（前置：PG 5432 + Redis 6379；机密经环境变量注入，不入库不入 git）
JWT_SECRET=<32B+ 随机串> \
AES_KEY=<32B 随机串> \
PLATFORM_CLIENT_SECRET=<平台域密钥> \
java -jar notification4j-app/target/notification4j-app-1.0.0.jar
```

- `server.port=9200`；控制台入口 `http://localhost:9200/`（消息中心 app 壳 `/nfy/tenant/app/messages`
  为 history 路由，直开/刷新由服务端 fallback 落到 index.html）；
  OpenAPI `http://localhost:9200/swagger-ui/index.html`；tracelog 控制台 `/tracelog/index.html`。
- 前端 SPA 托管由 `nfy.static-page.enabled=true`（app 出厂已开）装配
  （`NfyStaticPageConfig`，产物在 starter classpath `/nfy-console/`）；API 面
  `/nfy/api/**`、`/nfy/open/**`、`/nfy/platform/api/**` 优先，静态转发永不覆盖，未映射 API 路径照常 404。
- app 的 `application.yml` 里 Flyway 独立 datasource（绕 Druid Wall）指向 `localhost:5432/notification4j`，
  生产经 `spring.flyway.*` / `framework4j.datasource.datasources.default.*` 环境变量覆盖。

### 嵌入接入形态（业务方引 starter）

```xml
<dependency>
    <groupId>fun.commons.notification4j</groupId>
    <artifactId>notification4j-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
# 最小配置（嵌入默认值即安全面：mode=local、enable-api=false、engine.enabled=false——
# 后两者显式写出仅为可读性；静态页开关缺省 false，嵌入方零污染）
nfy:
  data:
    enabled: true          # 数据面闸（配合下方 @MapperScan）
  runtime:
    mode: local            # 嵌入形态：进程内直调 service
    enable-api: false      # 不暴露三域 API（引擎/门面照常可用）
    engine:
      enabled: false       # 外发引擎不跑（宿主业务自定）
```

```java
@SpringBootApplication
@MapperScan("fun.commons.notification4j.mapper")   // mapper 注册约定（同基包应用可省）
public class HostApplication { ... }
```

- 嵌入方发消息走 `NotifyClient` 门面（`nfy.runtime.client-enabled=true` 开启）。
- 若嵌入方也想托管消息中心页面（一般不需要），声明 `nfy.static-page.enabled=true` 即得全套静态托管。

### 跨进程接入形态（业务方引 client-starter，编码第 30 步）

业务方只发消息/公告、调**独立部署** notification4j，且不想承担 MyBatis/PG/引擎等数据面依赖时，
引轻量 starter（交付 `NotifyClient` Bean，HTTP + S2S JWT + HMAC 签名自动注入）：

```xml
<dependency>
    <groupId>fun.commons.notification4j</groupId>
    <artifactId>notification4j-client-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
# 最小配置（remote-url 缺失/mode=local 启动即失败；未配租户凭据 → 不签名不 NPE，远端会拒）
nfy:
  runtime:
    client-enabled: true
    mode: remote
    remote-url: http://notification4j-svc:9200
    remote-tenant-id: <业务方租户 OpenID>       # 独立部署平台「租户管理」获取
    remote-tenant-secret: <HMAC 签名密钥>       # 同上；X-Access-Key/X-Timestamp/X-Nonce/X-Signature 四元组
    connect-timeout-ms: 5000                    # 可选（默认 5s/10s；业务方自有唯一 RestTemplate Bean 时不改写）
    read-timeout-ms: 10000
```

```java
@Autowired NotifyClient notifyClient;   // 与嵌入形态同一接口（fun.commons.notification4j.client.NotifyClient）
notifyClient.send(tenantId, SendMessageRequest.of("TYPE", userIds, "标题", "内容", null, null, "biz-1"));
```

- 依赖极简：spring-boot-autoconfigure + framework4j-transport + jackson + spring-web，
  **零 MyBatis/零 JDBC/零 Redis**（framework4j-accesstoken 为 optional——需要 S2S JWT 自动注入时自行引入，
  缺席/生成失败自动降级为仅 HMAC 签名）。
- `tenantId` 参数在 remote 模式由服务端 token 决定（客户端忽略，同代码跨模式）。

**与全量 starter 的互斥/切换契约（第 30 步裁决）**：

1. **互斥**：两个 starter 二选一，不同时引。全量 starter 含数据面（MyBatis），client-starter 零数据面，
   同 classpath 属接入错误。
2. **API 四件同 FQCN**：`NotifyClient` / `SendMessageRequest` / `AnnounceRequest` / `NfyClientException`
   在两 jar 中同包同名同形态——嵌入（local）↔ 跨进程（remote）切换**只换依赖坐标，业务代码零改动**。
3. **实现类独立包名**：`RemoteNotifyClient` / `AuthenticatedHttpTransport` / `NfyClientProperties` 等实现件
   在独立包 `fun.commons.notification4j.remote`（配置类不同名，配置前缀仍为 `nfy.runtime`）——两 jar 意外共存时
   无同 FQCN 链接错配。
4. **共存确定性让位 + WARN**：万一共存，client-starter 的装配 `@AutoConfigureAfter(NfyAutoConfiguration)` +
   `@ConditionalOnMissingBean` 确定性让位（全量 starter 的 Bean 提供服务，行为仍正确），并打 WARN 提示移除其一；
   不做启动 fail-fast——fail-fast 只能基于 classpath 共存判定，会误伤测试编排（notification4j-it 合法地同引两者）。
5. **签名协议本地化**：client-starter 内置签名串构造/HMAC-SHA256（`NfyHmacSigner`），与 framework4j-signature
   的线协议逐字节一致（it 模块 `NfyClientStarterTest` 有等价回归闸）；本地化的理由是 framework4j-signature
   会传递 framework4j-web/redis，违背零中间件定位。

### 前端产物再生成（dist 精确拷贝，node_modules 永不入 jar）

```bash
cd frontend && pnpm build                      # vite base='/'，产物 dist/
rm -rf ../backend/notification-spring-boot-starter/src/main/resources/nfy-console
cp -R dist/ ../backend/notification-spring-boot-starter/src/main/resources/nfy-console/
cd ../backend
mvn -o install -pl notification-spring-boot-starter -DskipTests -Djacoco.skip=true
mvn -o clean package -pl notification4j-app -DskipTests   # 必须 clean：app 自身无改动时 jar 插件跳过重建，repackage 会沿用旧 fat jar（内嵌旧 starter）
```
