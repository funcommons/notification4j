# 配置参考：全量配置项字典

> 适用版本 **v1.4.0**（底座 framework4j v1.7.1，`backend/pom.xml:33`）。本文只收录**源码中有出处的键**：
> `@ConfigurationProperties` 字段（正文写连字符规范形）、`@ConditionalOnProperty` 闸门、`Environment.getProperty`
> 读取点、yml 占位符；出处标 `类名#字段` / `类名:行号` / `application.yml:行号`，fwk4j 默认值取自 v1.7.1 tag
> 的 properties 类源码。查无实据的键一律不收录。

## 1. 配置总览

| 配置面 | 前缀 | 键定义处 | 典型形态 |
|---|---|---|---|
| 本产品运行面 | `nfy.runtime.*`（含 `engine.*` 子段） | starter `NfyProperties` | 嵌入 / 独立 |
| 本产品装配闸 | `nfy.enabled` / `nfy.data.enabled` / `nfy.static-page.enabled` | starter 装配类（注解与 Environment 读取） | 嵌入 / 独立 |
| 跨进程客户端 | `nfy.runtime.*`（裁剪键集） | client-starter `NfyClientProperties` | 轻量接入宿主 |
| 独立部署壳 | `server.*` / `spring.flyway.*` 等 | app `application.yml` | 独立部署 |
| 底座框架 | `framework4j.*` | framework4j v1.7.1 properties 类（app yml 覆盖值） | 独立 / 嵌入（数据面） |
| 密钥注入 | `${JWT_SECRET}` 等 5 个占位符（§2/§6） | app yml | 独立部署 |

## 2. 必填项与安全基线

### 2.1 fail-fast 环境变量（无默认占位，缺失即启动失败）

| 占位符 | 落点键 | 校验 / 语义 | 出处 |
|---|---|---|---|
| `JWT_SECRET` | `framework4j.access-token.secret-key` | Spring 占位符不可解析即启动失败；值须非空且 ≥32 字符（HS256 256 位） | `application.yml:93`；fwk4j `AccessTokenProperties#secretKey`（@NotBlank + @Size(min=32)） |
| `AES_KEY` | `framework4j.sensitive.encryption-key` | 缺失即启动失败；AES-256-GCM 字段加密密钥（内部 SHA-256 派生 32 字节），租户密钥列依赖它 | `application.yml:122`；fwk4j `SensitiveProperties#encryptionKey` |
| `PLATFORM_CLIENT_SECRET` | `framework4j.tenant.platform.client-secret` | 缺失即启动失败；平台域（合成租户 tenant_id=0）登录凭据 | `application.yml:86`；fwk4j `Framework4jTenantProperties$Platform#clientSecret` |

### 2.2 可省占位符

| 占位符 | 落点键 | 缺省值 | 出处 |
|---|---|---|---|
| `HASH_SALT` | `framework4j.access-token.hash-salt` | `salt`（防 Redis 泄露反查会话 key；生产应显式改） | `application.yml:94` |
| `PLATFORM_CLIENT_ID` | `framework4j.tenant.platform.client-id` | `PLATFORM`（与 fwk4j 默认一致） | `application.yml:85` |

### 2.3 出厂安全基线（独立部署 app 出厂值；均位于 dev 段内，见 §4 结构注）

- **签名出厂不强制**：`framework4j.signature.path-patterns: []`（`application.yml:118`，D-1）——SPA 与 S2S 共用 runtime 路径，强制签名令 SPA 全拒 10101；S2S 需要时自行加回 pattern（密钥由 `NfyTenantSecretProvider` 解析）。
- **Druid filters 去 wall**：`stat,slf4j`（`application.yml:58`）——wall 不识别 PG 方言（`SKIP LOCKED` / 部分索引 WHERE）；fwk4j 出厂默认 `stat,wall,slf4j`（`DataSourceProperties$DruidConfig#filters`）。
- **四闸默认关**：`enable-api` / `engine.enabled` / `client-enabled` / `static-page.enabled` 缺省全 `false`
  （`NfyProperties` 字段默认 + `NfyStaticPageConfig:54`），嵌入方零声明零污染。
- **总闸逃生门**：`nfy.enabled=false` 时接管 veto 全部放行，宿主恢复原生装配（`NfyDataPlaneTakeoverFilter:79`）。

## 3. `nfy.*` 本产品键

### 3.1 `nfy.runtime.*`（starter `NfyProperties`，prefix = `nfy.runtime`）

| 键 | 类型 | 默认 | 说明 | 出处 |
|---|---|---|---|---|
| `nfy.runtime.mode` | String | `local` | `local`=进程内直连 service / `remote`=HTTP 调远端；仅此二值（@Pattern） | `NfyProperties#mode` |
| `nfy.runtime.enable-api` | boolean | `false` | 三域 REST API 控制器装配开关（开启还须 `nfy.data.enabled=true`，否则启动期 WARN 提示 API 将 404） | `NfyProperties#enableApi`；`NfyAutoConfiguration:405/:115-119` |
| `nfy.runtime.remote-url` | String | null | remote 模式服务端点（如 `http://notification4j-svc:9200`）；remote 模式必填，缺失启动即失败 | `NfyProperties#remoteUrl`；`NfyAutoConfiguration:78`（Assert） |
| `nfy.runtime.remote-tenant-id` | String | null | 业务方租户 OpenID（S2S JWT claims + X-Access-Key）；**配置即触发** `AuthenticatedHttpTransport` 装配（presence 条件） | `NfyProperties#remoteTenantId`；`NfyAutoConfiguration:41` |
| `nfy.runtime.remote-tenant-secret` | String | null | 租户 HMAC-SHA256 签名密钥（X-Signature 四元组）；未配则不签名不 NPE，远端会拒 | `NfyProperties#remoteTenantSecret` |
| `nfy.runtime.client-enabled` | boolean | `false` | `NotifyClient` 门面装配开关（true 时按 mode 分派 local/remote 实现） | `NfyProperties#clientEnabled`；`NfyAutoConfiguration:68` |

### 3.2 `nfy.runtime.engine.*`（外发引擎八参数 + SMTP 五参数，共 13 字段，`NfyProperties$Engine`）

| 键 | 类型 | 默认 | 说明 | 出处 |
|---|---|---|---|---|
| `nfy.runtime.engine.enabled` | boolean | `false` | 引擎总开关（SmartLifecycle `start()` 判定；嵌入形态业务方自带调度时保持关） | `Engine#enabled`；`DeliveryEngine:110` |
| `nfy.runtime.engine.scan-interval-ms` | long | `5000` | 待投递行扫描周期（ms） | `Engine#scanIntervalMs` |
| `nfy.runtime.engine.batch-size` | int | `50` | 单批领取行数 | `Engine#batchSize` |
| `nfy.runtime.engine.reaper-interval-ms` | long | `60000` | reaper 周期：回收卡在 SENDING 的行（ms） | `Engine#reaperIntervalMs` |
| `nfy.runtime.engine.sending-stale-ms` | long | `600000` | SENDING 视为卡死的阈值（ms，10min） | `Engine#sendingStaleMs` |
| `nfy.runtime.engine.backoff-seconds` | String | `"60,300,900"` | 重试退避秒序列（1/5/15min，契约 §4.3）；重试次数超出段数取末段 | `Engine#backoffSeconds`；`DeliveryEngine:226` |
| `nfy.runtime.engine.rate-limit-per-minute` | int | `18` | 每渠道每分钟投递上限（内置固定窗；钉钉/企微硬限额 20/min 留余量）；**0 = 不限速** | `Engine#rateLimitPerMinute`；`DeliveryEngine:42-45` |
| `nfy.runtime.engine.worker-count` | int | `4` | 投递工作线程数（跨渠道共享池，V1.0）；小于 1 按 1 计 | `Engine#workerCount`；`DeliveryEngine:87` |
| `nfy.runtime.engine.mail-host` | String | `""` | SMTP 主机；**为空时 EMAIL 渠道直接投递失败**（走重试 → DEAD） | `Engine#mailHost`；`EmailSender`（类注） |
| `nfy.runtime.engine.mail-port` / `mail-from` / `mail-username` / `mail-password` | — | `25` / `""` / `""` / `""` | SMTP 端口 / 发件人 / 认证用户 / 认证密码 | `Engine#mailPort/#mailFrom/#mailUsername/#mailPassword` |

> 注意：`NfyProperties$Engine` 类注释写作「`nfy.engine.*` 前缀」是**陈旧注释**——Engine 是 `nfy.runtime` 前缀类
> 的嵌套字段，实际绑定键即 `nfy.runtime.engine.*`（app `application.yml:178-186` 同款）。

### 3.3 装配闸门键（无 properties 类，纯条件/环境读取）

| 键 | 类型 | 默认 | 说明 | 出处 |
|---|---|---|---|---|
| `nfy.enabled` | Boolean | `true` | 总闸：`false` 时 starter 的原生装配 veto 全部放行（宿主自管数据面/缓存面的逃生门） | `NfyDataPlaneTakeoverFilter:49/:79` |
| `nfy.data.enabled` | Boolean | `true`（matchIfMissing） | 数据面闸：消息/渠道/订阅/公告等全部 service + API 的装配前提（还须上下文存在 `SqlSessionFactory`） | `NfyAutoConfiguration:134`（NfyDataConfig）；`:115`（矛盾告警） |
| `nfy.static-page.enabled` | Boolean | `false`（matchIfMissing=false） | 消息中心 SPA 静态托管开关（产物在 `classpath:/nfy-console/`，避开 Boot 默认静态位，嵌入零污染） | `NfyStaticPageConfig:54` |

### 3.4 client-starter 侧键（`NfyClientProperties`，**同前缀 `nfy.runtime`**）

轻量接入宿主与嵌入宿主共用前缀（yml 两 starter 间切换零改动）；装配闸同为 `nfy.runtime.client-enabled=true`（`ClientAutoConfiguration:50`）。

| 键 | 类型 | 默认 | 说明 | 出处 |
|---|---|---|---|---|
| `nfy.runtime.mode` | String | `remote` | client-starter 无数据面，仅支持 remote；配 `local` 装配期 Assert fail-fast | `NfyClientProperties#mode`；`ClientAutoConfiguration:97` |
| `nfy.runtime.remote-url` | String | null | 必填（缺失启动即失败） | `NfyClientProperties#remoteUrl`；`ClientAutoConfiguration:100` |
| `nfy.runtime.remote-tenant-id` | String | null | 同 §3.1（S2S JWT claims + X-Access-Key） | `NfyClientProperties#remoteTenantId` |
| `nfy.runtime.remote-tenant-secret` | String | null | 同 §3.1（HMAC 签名密钥） | `NfyClientProperties#remoteTenantSecret` |
| `nfy.runtime.connect-timeout-ms` | int | `5000` | HTTP 连接超时（仅 starter 新建 RestTemplate 时生效；宿主已有唯一 RestTemplate Bean 不改写） | `NfyClientProperties#connectTimeoutMs`；`ClientAutoConfiguration:136-146` |
| `nfy.runtime.read-timeout-ms` | int | `10000` | HTTP 读超时（同上） | `NfyClientProperties#readTimeoutMs` |

## 4. 独立部署壳键（app `application.yml` 首段，无条件生效）

| 键 | 出厂值 | 说明 | 出处 |
|---|---|---|---|
| `server.port` / `server.shutdown` | `9200` / `graceful` | HTTP 端口；优雅停机（SIGTERM 后处理完在途请求再退出） | `application.yml:2-3` |
| `spring.application.name` / `spring.profiles.active` | `notification4j-backend` / `dev` | 应用名（SQL 追踪 topic 等自动取用）；出厂激活 dev 段（见下方结构注） | `application.yml:6-9` |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | 优雅停机最长等待 | `application.yml:11` |
| `spring.flyway.enabled` / `locations` | `true` / `classpath:db/migration` | 结构迁移开关；脚本随 starter jar 提供（V1.0.0/V1.0.1） | `application.yml:16-17` |
| `spring.flyway.baseline-on-migrate` / `validate-on-migrate` | `false` / `true` | 全新库从头执行、非空库须先人工清理不静默 baseline；迁移前校验 | `application.yml:18-19` |
| `spring.flyway.url` | `jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified` | Flyway 独立 datasource（绕 Druid Wall 拦 PG 方言）；生产经环境变量覆盖 | `application.yml:21` |
| `spring.flyway.user` / `spring.flyway.password` | `admin` / `test@2026` | Flyway 连接凭据（开发出厂值，生产必改） | `application.yml:22-23` |

**多文档结构注**：app 出厂 `application.yml` 是两段式——首段（上表）无条件生效；第二段以 `spring.config.activate.on-profile: dev` 打头（`application.yml:25-29`），**framework4j.\*、mybatis-plus.\*、nfy.\* 全部位于 dev 段内**。生产部署需自带同款键的 profile 或外置配置（`spring.config.import` / 环境变量 / 命令行），否则 framework4j 数据面闸（缺省 false）不会开启。

## 5. `framework4j.*` 键字典（v1.7.1；「app」列 = 独立部署 dev 段出厂值，`—` = 未配置走默认）

### 5.1 `framework4j.datasource.*`（fwk4j `MultiDataSourceAutoConfiguration` + `DataSourceProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.datasource.enabled` | boolean | `false`（缺配即关） | `true` | 多数据源接管总闸；同时是 starter veto 原生 DataSource/Druid/MybatisPlus/TxManager 的激活条件 | `MultiDataSourceAutoConfiguration:57`；`NfyDataPlaneTakeoverFilter:52` |
| `...datasources.default.url` | String | null | PG JDBC URL（`?stringtype=unspecified`） | 必填 | `DataSourceProperties#url`；`application.yml:51` |
| `...datasources.default.username` / `.password` | String | null | `admin` / `test@2026` | 必填 | `DataSourceProperties#username/#password` |
| `...datasources.default.driver-class-name` | String | null（自动检测） | — | 驱动类名（可配未用） | `DataSourceProperties#driverClassName` |
| `...datasources.default.druid.filters` | String | `stat,wall,slf4j` | `stat,slf4j` | **去 wall**（不识别 PG 方言，见 §2.3） | `DataSourceProperties$DruidConfig#filters`；`application.yml:58` |
| `...druid.initial-size` / `min-idle` / `max-active` | int | `5` / `5` / `20` | `10` / `10` / `50` | 连接池容量（app 注：默认 20 太小，生产 50） | `DruidConfig`；`application.yml:59-61` |
| `...druid.max-wait` / `test-while-idle` / `validation-query` | long / boolean / String | `60000` / `true` / `SELECT 1` | `1000` / 同默认 / 同默认 | 获取连接超时 ms（app 注：默认 60s 太长）；空闲检测 | `DruidConfig`；`application.yml:62-64` |
| `...druid.test-on-borrow` / `test-on-return` / `time-between-eviction-runs-millis` / `min-evictable-idle-time-millis` / `connection-properties` | — | `false`/`false`/`60000`/`300000`/null | — | 可配未用（走默认） | `DruidConfig` |
| `...datasources.default.mybatis-plus.mapper-locations` / `type-aliases-package` / `config-location` | — | null | — | Mapper XML / 别名包 / MyBatis 配置文件位置（本产品全注解 mapper，未用） | `DataSourceProperties$MybatisPlusConfig` |
| `...datasources.default.mybatis-plus-plugins.pagination` / `block-attack` | boolean | `true` / `true` | 同默认 | 分页 / 防全表更新插件 | `MybatisPlusPluginsConfig`；`application.yml:67-68` |
| `...mybatis-plus-plugins.optimistic-lock` | boolean | `false` | `true` | 乐观锁插件（需实体 @Version） | `MybatisPlusPluginsConfig#optimisticLock`；`application.yml:65` |
| `...mybatis-plus-plugins.db-type` | String | null（自动检测） | `POSTGRE_SQL` | 分页方言 | `MybatisPlusPluginsConfig#dbType`；`application.yml:69` |
| `framework4j.datasource.sql-tracing.enabled` / `mode` | boolean / enum | `true`（matchIfMissing）/ `ALL` | 同默认 | Druid Filter 注入 `/*traceid=xxx*/` 注释；mode：`DISABLED`/`WRITE_ONLY`/`ALL` | `SqlTracingAutoConfiguration:23-28`；`SqlTracingProperties#mode`；`application.yml:71-73` |

### 5.2 `framework4j.redis.*`（fwk4j `MultiRedisAutoConfiguration` + `RedisDataSourceProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.redis.enabled` | boolean | `false`（缺配即关） | `true` | redis 接管总闸；同时是 starter veto RedissonV2/V4 的条件 | `MultiRedisAutoConfiguration:54`；`NfyDataPlaneTakeoverFilter:55` |
| `...datasources.default.host` / `port` | String / int | `localhost` / `6379` | 同默认 | 连接地址 | `RedisDataSourceProperties#host/#port`；`application.yml:36-37` |
| `...datasources.default.database` / `password` / `timeout` / `template-type` | — | `0` / null / `3000ms` / `STRING` | — | 可配未用；template-type：STRING（StringRedisTemplate）/ OBJECT | `RedisDataSourceProperties` |
| `...datasources.default.lettuce.pool.max-active` / `max-idle` / `min-idle` / `max-wait` | int / long | `8` / `8` / `0` / `-1` | `50` / `20` / `5` / — | Lettuce 连接池（max-wait 可配未用） | `RedisDataSourceProperties$PoolConfig`；`application.yml:43-46` |
| `...datasources.default.redisson.enabled` | boolean | `false` | `false` | 显式关闭（D-4：本产品全链零 redisson，且其客户端端点不随 host/port 覆盖） | `RedisDataSourceProperties$RedissonConfig`；`application.yml:40-41` |

### 5.3 `framework4j.tenant.*`（fwk4j `Framework4jTenantProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.tenant.enabled` | boolean | `false` | `true` | 租户模块总开关（含 DDL 与认证端点注册） | `Framework4jTenantProperties#enabled`；`application.yml:75` |
| `framework4j.tenant.table-prefix` | String | `""`（@NotBlank + @Pattern `^[a-z][a-z0-9]{1,9}_$`） | `nfya_` | 租户表 = `{prefix}tenant` → `nfya_tenant` | `#tablePrefix`；`application.yml:76` |
| `framework4j.tenant.ddl-mode` | enum | `AUTO`（幂等建表） | `PROVIDED` | 结构由 Flyway 管，框架不执行 DDL | `#ddlMode`；`application.yml:77` |
| `framework4j.tenant.default-tenant-id` | Long | null（多租户模式） | — | 可配未用（配置真实租户 id 即单租户免 token 模式） | `#defaultTenantId` |
| `...tenant.auth.enabled` / `path` | boolean / String | `true` / `/api/v1/auth/token` | — / `/nfy/api/v1/auth/token` | 内置 client_credentials 认证端点注册；端点路径 | `$Auth`；`NfyDataPlaneTakeoverTest:66`；`application.yml:79` |
| `...tenant.auth.token-type` / `expire-seconds` | String / long | `TENANT` / `28800`（8h；@Max 43200） | 同默认 | 签发型别（ADR-0007）；token 有效期（M2M 无 refresh，硬上限 12h） | `$Auth`；`application.yml:80-81` |
| `...tenant.auth.max-fail` / `lock-minutes` / `embed-claims` | int / int / boolean | `5` / `15` / `true` | 同默认 / — | 连续失败锁定（防爆破）；签发时业务 claims 嵌入 JWT payload | `$Auth`；`application.yml:82-83` |
| `...tenant.platform.client-id` / `client-secret` | String | `PLATFORM` / `""` | `${PLATFORM_CLIENT_ID:PLATFORM}` / `${PLATFORM_CLIENT_SECRET}` | 平台合成租户（tenant_id=0）凭据 | `$Platform`；`application.yml:85-86` |
| `...tenant.secret.grace-hours` | int | `24` | `24` | 密钥轮换宽限期（租户设计 §5.5 双版本过渡） | `$Secret#graceHours`；`application.yml:88` |
| `...tenant.platform.tenant-id` / `...tenant.registration-key.*` / `...tenant.rls.mode` | — | `0` / 关 / `OFF` | — | 平台身份 id、注册码通道、RLS——可配未用 | `$Platform#tenantId` / `$RegistrationKey` / `$Rls` |

### 5.4 `framework4j.access-token.*`（fwk4j `AccessTokenProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.access-token.enabled` | boolean | `false` | `true` | JWT + Redis 双验鉴权开关 | `AccessTokenProperties#enabled`；`application.yml:91` |
| `...access-token.secret-key` | String | null（@NotBlank ≥32） | `${JWT_SECRET}` | HS256 签名密钥（§2.1） | `#secretKey`；`application.yml:93` |
| `...access-token.hash-salt` / `redis-name` | String | `""`（@NotBlank）/ `default` | `${HASH_SALT:salt}` / `default` | 会话 key 哈希加盐；会话 Redis 数据源名 | `#hashSalt/#redisName`；`application.yml:92-94` |
| `...access-token.expire-time` / `path-patterns` | long / List | `7200` / `["/**"]` | —（生效） | 全局默认过期（被 policies 覆盖）；拦截路径（由注解决定是否真鉴权） | `#expireTime` / `#pathPatterns` |
| `...access-token.exclude-path-patterns` | List | `[]` | 7 项放行（auth 端点 / `/nfy/open/**` / ops health / swagger / tracelog 页） | open 域无鉴权（IP 限流兜底） | `#excludePathPatterns`；`application.yml:95` |
| `...access-token.policies.<TYPE>.key` | List/String | null | `tenant_id`（TENANT/PLATFORM/OPS 三型别） | **互斥键字段名（claims 必需字段，非签名密钥）**；单字符串有便捷 setter | `$Policy#key`（setKeyFromString）；`application.yml:96-107` |
| `...policies.<TYPE>.expire-time` | Long | null（回落全局） | `28800` ×3 | 型别级过期覆盖 | `$Policy#expireTime` |
| `...policies.<TYPE>.auto-renew` / `renew-increment` | Boolean / Long | null | `true` / `1800`（仅 TENANT） | 自动续期与步长（app 出厂仅租户型别开） | `$Policy`；`application.yml:100-101` |
| `...policies.<TYPE>.max-usage` / `activation-time-limit` / `refresh-expire-time` / `max-rotations` | — | null/null/2592000/20 | — | 可配未用（本产品 M2M 不走 refresh 家族） | `$Policy` |

### 5.5 `framework4j.signature.*`（fwk4j `SignatureProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.signature.enabled` | boolean | `true` | `true` | 签名校验开关（拦不拦截另看 path-patterns） | `SignatureProperties#enabled`；`application.yml:109` |
| `...signature.timestamp-tolerance-ms` / `nonce-ttl-seconds` | long | `300000`（±5min）/ `600`（10min） | 同默认 | 时间戳容忍；nonce 防重放 TTL | `#timestampToleranceMs/#nonceTtlSeconds`；`application.yml:110-111` |
| `...signature.nonce-key-prefix` / `redis-name` | String | `signature:nonce` / `default` | `notification4j:sig:nonce` / `default` | nonce Redis key 前缀；nonce 存储 Redis 名 | `#nonceKeyPrefix/#redisName`；`application.yml:112-113` |
| `...signature.path-patterns` | List | `[]` | `[]` | **出厂空 = 不强制签名**（§2.3；S2S 需要时加 `/nfy/api/v1/runtime/**` 等） | `#pathPatterns`；`application.yml:118` |
| `...signature.exclude-path-patterns` | List | `[]` | `[]` | 排除路径 | `#excludePathPatterns`；`application.yml:119` |
| `...signature.header-names.*`（access-key/timestamp/nonce/signature） | String | `X-Access-Key` / `X-Timestamp` / `X-Nonce` / `X-Signature` | — | 四元组 header 名（可配未用） | `$HeaderNames` |

### 5.6 `framework4j.sensitive.*`（fwk4j `SensitiveProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.sensitive.enabled` | boolean | `true` | `true` | 字段加密/脱敏开关 | `SensitiveProperties#enabled`；`application.yml:121` |
| `...sensitive.encryption-key` / `iv-length` / `tag-bits` | — | null / `12` / `128` | `${AES_KEY}` / — / — | AES-256-GCM 密钥（§2.1）；IV 长度与 Tag 位数（可配未用） | `SensitiveProperties`；`application.yml:122` |

### 5.7 `framework4j.idempotency.*`（fwk4j `IdempotencyProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.idempotency.enabled` | boolean | `false` | `true` | 幂等键拦截开关 | `IdempotencyProperties#enabled`；`application.yml:124` |
| `...idempotency.header-name` / `key-prefix` | String | `Idempotency-Key` / `idem` | 同默认 / `notification4j:idem` | 幂等头（三方传 UUID v4）；Redis key 前缀 | `#headerName/#keyPrefix`；`application.yml:125-126` |
| `...idempotency.ttl-seconds` / `body-hash-required` / `redis-name` | long / boolean / String | `172800`（48h）/ `true` / `default` | 同默认 | 回放缓存 TTL；防同 key 不同 body；存储 Redis 名 | `#ttlSeconds/#bodyHashRequired/#redisName`；`application.yml:127-129` |
| `...idempotency.path-patterns` | List | `["/api/**"]` | runtime 三写面（messages/channels/subscriptions） | 仅覆盖业务声明的幂等端点 | `#pathPatterns`；`application.yml:131-134` |
| `...idempotency.exclude-path-patterns` | List | `[]` | — | 可配未用 | `#excludePathPatterns` |

### 5.8 `framework4j.audit.*`（fwk4j `AuditProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.audit.enabled` | boolean | `true` | `true` | 审计日志开关 | `AuditProperties#enabled`；`application.yml:136` |
| `...audit.table-name` 及可配未用项（hash-chain-enabled / hash-algorithm / actor-header / actor-fallback / ip-header） | — | `audit_log` 等 | `nfyp_audit_log` | 审计表名（守项目简码规范）；防篡改链与操作人/IP 来源 header 默认见 fwk4j 类 | `AuditProperties`；`application.yml:137` |

### 5.9 `framework4j.tracelog.*`（fwk4j `TraceLogProperties`；控制台 `/tracelog/index.html`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.tracelog.enabled` / `redis-name` | boolean / String | `false`（opt-in）/ `default` | `true` / `default` | 动态追踪日志总开关（同时触发 starter 装 `NfyTraceLogAuthValidator`，OPS-only）；日志聚合 Redis 名 | `TraceLogProperties`；`application.yml:141-142`；`NfyAutoConfiguration:99` |
| `...tracelog.storage.key-prefix` | String | `trace_log` | `notification4j:trace_log` | 日志 List key：`{prefix}:{traceId}` | `$Storage#keyPrefix`；`application.yml:144` |
| `...tracelog.collection.fallback-dir` | String | `/var/log/framework4j-tracelog/fallback` | `/tmp/notification4j-tracelog-fallback` | Redis 故障降级目录（容器化需挂载） | `$Collection#fallbackDir`；`application.yml:146` |
| `...tracelog.sync.channel` | String | `channel:log_switch` | `notification4j:channel:log_switch` | 开关广播频道（**段名是 sync 非 switch**） | `$Switch#channel`；`application.yml:148` |
| `...tracelog.elevation.allowed-packages` | List | `["com.yourcompany"]` | `["fun.commons.notification4j"]` | 提权作用域限业务包 | `$Elevation#allowedPackages`；`application.yml:150-151` |
| `...tracelog.elevation.default-level` | Level | `DEBUG` | `DEBUG` | 提权目标级别 | `$Elevation#defaultLevel`；`application.yml:152` |
| `...tracelog.api.require-auth` / `max-return-logs` | boolean / int | `true` / `1000` | `true` / `500` | 控制台 API 强制鉴权（未配 Validator 时 fwk4j 启动 fail-fast）；查询单次返回上限 | `$Api`；`application.yml:154-155` |
| `...tracelog` 其余段（storage/collection/api/export/console/tenant） | — | 见 fwk4j 类 | — | 可配未用（容量、脱敏、导出、IP 名单等） | `TraceLogProperties` |

### 5.10 `framework4j.rate-limit.*`（fwk4j `RateLimitProperties`）

| 键 | 类型 | fwk4j 默认 | app | 说明 | 出处 |
|---|---|---|---|---|---|
| `framework4j.rate-limit.enabled` | boolean | `true` | `true` | 分布式限流开关 | `RateLimitProperties#enabled`；`application.yml:157` |
| `...rate-limit.redis-name` / `key-prefix` | String | `default` / `ratelimit` | `default` / `notification4j:rl` | 计数 Redis 名；key 前缀 | `#redisName/#keyPrefix`；`application.yml:158-159` |
| `...rate-limit.default-scope` | String | `ip` | `APP` | 限流维度。**fwk4j v1.7.1 仅认小写 `ip`/`user`/`app`/`global`**（`app`=X-Access-Key 头，非 tenant_id）；`APP` 未命中任何 switch 分支 → 落 default 分支实际按 **IP** 维限流（key=`notification4j:rl:APP:{ip}:{path}`），见 §9 勘误 | `#defaultScope`；`RateLimitKeyResolver:57-77`；`application.yml:160` |
| `...rate-limit.default-limit` / `default-window` / `default-algorithm` | int / String | `100` / `1m` / `sliding_window` | 同默认 / 同默认 / `SLIDING_WINDOW` | 窗口内上限；窗口时长；Lua 滑动窗 / token_bucket | `RateLimitProperties`；`application.yml:161-163` |
| `...rate-limit.path-patterns` | List | `[]` | runtime/assets/platform-assets/open 四组 | open 域 IP 限流兜底（评审 P1） | `#pathPatterns`；`application.yml:164` |
| `...rate-limit.whitelist-paths` | List | `["/actuator/**","/health/**","/v1/auth/login"]` | `["/nfy/api/v1/auth/token"]` | 完全跳过限流 | `#whitelistPaths`；`application.yml:165` |
| `...rate-limit.exclude-path-patterns` / `include-headers` / `whitelist-ips` | — | `[]`/`true`/`["127.0.0.1","::1"]` | — | 可配未用 | `RateLimitProperties` |

## 6. 环境变量占位符全集（app yml）

即 §2.1/§2.2 五个占位符。环境变量同时是生产覆盖 `spring.flyway.url/user/password` 与
`framework4j.datasource.datasources.default.*` 的推荐通道（backend/README「部署」节口径）。

## 7. 配置样例

### 7.1 嵌入宿主最小配置（取证：`NfyDataPlaneTakeoverTest` properties + app yml）

```yaml
framework4j:
  datasource:
    enabled: true                          # 接管闸（缺省 false；v1.3 起零 exclude、零 spring.datasource）
    datasources:
      default: { url: jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified,
                  username: admin, password: test@2026,
                  druid: { filters: stat,slf4j } }   # 去掉 wall（PG 方言不兼容，§2.3）
  redis:
    enabled: true                          # 缺省 false
    datasources:
      default: { host: localhost, port: 6379 }

nfy:
  runtime:
    client-enabled: true                   # NotifyClient 门面（缺省 false）
    mode: local                            # 缺省即 local
```

配套：启动类 `@MapperScan("fun.commons.notification4j.mapper")`；雪花 id 零配置
（`NfyMybatisPlusSupportAutoConfiguration` 兜底）；鉴权/限流等 fwk4j 键按 §5 按需补齐。

### 7.2 独立部署全量样例（app yml 精简版，dev 段口径；与 fwk4j 默认同值的键不重复写出，见 §5 表）

```yaml
server: { port: 9200, shutdown: graceful }
spring:
  flyway: { enabled: true, locations: classpath:db/migration,
            url: jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified,
            user: admin, password: test@2026 }
framework4j:
  datasource:
    enabled: true
    datasources:
      default: { url: jdbc:postgresql://localhost:5432/notification4j?stringtype=unspecified,
                  username: admin, password: test@2026,
                  druid: { filters: stat,slf4j, initial-size: 10, min-idle: 10, max-active: 50,
                           max-wait: 1000, test-while-idle: true, validation-query: SELECT 1 },
                  mybatisPlusPlugins: { optimistic-lock: true, pagination: true,
                                        block-attack: true, dbType: POSTGRE_SQL } }   # 余键见 §5.1
    sql-tracing: { enabled: true, mode: ALL }
  redis:
    enabled: true
    datasources:
      default: { host: localhost, port: 6379, redisson: { enabled: false },
                  lettuce: { pool: { max-active: 50, max-idle: 20, min-idle: 5 } } }
  tenant: { enabled: true, table-prefix: nfya_, ddl-mode: PROVIDED,
            auth: { path: /nfy/api/v1/auth/token },
            platform: { client-id: '${PLATFORM_CLIENT_ID:PLATFORM}',
                        client-secret: '${PLATFORM_CLIENT_SECRET}' } }
  access-token:
    enabled: true
    secret-key: ${JWT_SECRET}
    hash-salt: ${HASH_SALT:salt}
    exclude-path-patterns: ["/nfy/api/v1/auth/token", "/nfy/open/**", "/nfy/api/v1/ops/health",
                            "/swagger-ui/**", "/v3/api-docs/**", "/tracelog", "/tracelog/**"]
    policies:
      TENANT:   { key: tenant_id, expire-time: 28800, auto-renew: true, renew-increment: 1800 }
      PLATFORM: { key: tenant_id, expire-time: 28800 }
      OPS:      { key: tenant_id, expire-time: 28800 }
  signature: { enabled: true, nonce-key-prefix: notification4j:sig:nonce, path-patterns: [] }
  sensitive: { enabled: true, encryption-key: ${AES_KEY} }
  idempotency: { enabled: true, key-prefix: notification4j:idem,
                 path-patterns: ["/nfy/api/v1/runtime/messages", "/nfy/api/v1/runtime/channels",
                                 "/nfy/api/v1/runtime/subscriptions"] }
  audit: { enabled: true, table-name: nfyp_audit_log }
  tracelog:
    enabled: true
    storage: { key-prefix: notification4j:trace_log }
    collection: { fallback-dir: /tmp/notification4j-tracelog-fallback }
    sync: { channel: notification4j:channel:log_switch }
    elevation: { allowed-packages: [fun.commons.notification4j], default-level: DEBUG }
    api: { require-auth: true, max-return-logs: 500 }
  rate-limit: { enabled: true, key-prefix: notification4j:rl, default-scope: APP,
                default-algorithm: SLIDING_WINDOW,
                path-patterns: ["/nfy/api/v1/runtime/**", "/nfy/api/v1/assets/**",
                                "/nfy/api/v1/platform/assets/**", "/nfy/open/**"],
                whitelist-paths: ["/nfy/api/v1/auth/token"] }
nfy:
  runtime:
    enable-api: true                       # 独立部署：三域 API 开启
    mode: local
    engine: { enabled: true, backoff-seconds: "60,300,900", rate-limit-per-minute: 18 }
  static-page: { enabled: true }           # SPA 入 jar 免双部署（缺省 false）
```

## 8. 配置优先级与覆盖

- Spring Boot 标准序：**命令行参数 > 环境变量 > 外置 application-{profile}.yml > jar 内 application.yml**；
  app 出厂 dev 段（§4 结构注）即被生产 profile / 环境变量同键替换。
- `NfyProperties` 为 `@Validated`：`nfy.runtime.mode` 传 `local|remote` 以外值启动即校验失败。
- starter 装配遵循 `@ConditionalOnMissingBean`：宿主自声明同名 Bean（`NotifyClient`、
  `TraceLogAuthValidator`、`IdentifierGenerator` 等）优先于自动装配。

## 9. 已登记未消费项与口径勘误

**DB 存储的租户级配置（非 yml 键，`nfya_tenant` JSONB 列，`TenantEntity`）**：

| 列 | 登记结构 | 运行时消费 | 出处 |
|---|---|---|---|
| `oem` | `{theme, title, logo, hosts}` | **仅 `hosts`**（API-OEM-001：嵌入 postMessage origin 白名单，`OemService`/`NfyRuntimeOemController`）；theme/title/logo 仅存储待前端消费 | `PatchTenantRequest:8`；`OemService:31-36` |
| `privileges` | `{signature, delivery}` | **仅存储**（PTE-002 patch 直写列，无运行时读取点） | `PlatformTenantService:83-84/:141` |
| `config` | `{retentionDays, alert_user_ids, mail}` | **仅存储**（登记未消费） | `PatchTenantRequest:8` |

**口径勘误（源码注释陈旧，非文档废弃键）**：`NfyProperties$Engine` 与 `EmailSender` 类注写作「`nfy.engine.*`」，实际绑定前缀为 `nfy.runtime.engine.*`（§3.2）；`application.yml:185` 注释「0=关」准确语义是「0 = 不限速」（`DeliveryEngine:42-45`）；`application.yml:160` 注释「按 Tenant 维度限流（从 TokenContext 取 tenant_id）」与实现不符——fwk4j v1.7.1 `RateLimitKeyResolver` 的 scope 分支仅小写 `ip`/`user`/`app`/`global` 且无 tenant 维（`app`=`X-Access-Key` 头），`APP` 未命中分支落 default → 实际**按客户端 IP 限流**（每路径独立桶），yml 注释与本文 §5.10 表均已按实现口径修正。

**冗余键（登记在 app yml 但当前形态不消费）**：`mybatis-plus.configuration.map-underscore-to-camel-case`（`application.yml:169-171`）——原生 `MybatisPlusAutoConfiguration` 已被接管过滤器 veto，fwk4j registrar 只读 `framework4j.datasource.datasources.*.mybatis-plus.*` 三键（`MultiDataSourceAutoConfiguration:397-437`）；行为不受影响（MP `MybatisConfiguration` 构造默认 `mapUnderscoreToCamelCase=true`，MybatisConfiguration.java:92）。

**废弃键清单（文档历史有、源码无）**：经全量核对（本手册既有章节、backend/README、docs/design），**未发现**文档中出现而源码无出处的配置键。
