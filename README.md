# notification4j —— 通用应用系统站内消息/公告/站外通知微中台

多租户模式提供「站内消息 + 公告 + 站外渠道（钉钉/企微/飞书/邮件）通知」统一能力：
应用系统（租户）通过 **OpenAPI / Java Starter（NotifyClient）** 发消息，终端用户通过
**嵌入式微前端消息中心** 查看通知、自注册 IM 群 webhook 渠道、配置类型×渠道订阅矩阵。

## 目录结构

```
notification4j/
├── documents/          # 产品/数据库/接口/技术方案文档 + adr/
├── backend/            # Maven 多模块（parent: notification4j-parent）
│   ├── notification-spring-boot-starter/   # 业务方唯一依赖（API/服务/引擎/client 门面）
│   ├── notification4j-app/                 # 独立部署形态（flyway+密钥+三域 API+引擎）
│   └── notification4j-it/                  # 集成测试（Testcontainers PG16+Redis7，109 用例）
└── frontend/           # Vue3 消息中心（消息/公告/渠道/订阅/投递五页 + 铃铛，358 vitest）
```

## 后端快速开始

```bash
cd backend
# 依赖：JDK17、本地 Docker（Testcontainers）、本地 m2 离线构建
mvn -o install -DskipTests     # 构建并安装 starter 到本地仓库
mvn -o test -pl notification4j-it   # 109 项集成测试
```

独立部署（app）需要环境变量：`PLATFORM_CLIENT_SECRET`、`JWT_SECRET`、`AES_KEY`（fail-fast 无默认值），
以及 PostgreSQL + Redis；`application.yml` 已含出厂段（三域 API + 外发引擎双开）。

### 业务方嵌入（starter）

```java
// application.yml: nfy.runtime.client-enabled=true（local 默认；remote 需 remote-url）
@Autowired NotifyClient notifyClient;

notifyClient.send(tenantId, SendMessageRequest.of(
    "ORDER", List.of("u_1"), "订单已支付", "**OD1** 已支付", "NORMAL", null, "biz-1"));
notifyClient.announce(tenantId, AnnounceRequest.of("停机公告", "内容", "IMPORTANT", 1, null, null));
```

### 业务方前端嵌入

```html
<iframe src="{nfy-host}/nfy/tenant/page/bell" style="width:48px;height:48px"></iframe>
<script>
  window.addEventListener('message', (e) => {
    if (e.data?.type === 'NFY_READY') {
      iframe.contentWindow.postMessage(
        { type: 'NFY_TOKEN', token: '<租户后端代换的短期 token>', user_id: 'u_1' }, '*');
    }
  });
</script>
```

## 前端快速开始

```bash
cd frontend
pnpm install --prefer-offline
pnpm verify        # vue-tsc + eslint + vitest（358 用例）
pnpm dev           # http://localhost:5173
```

## 测试与验证门

| 门 | 命令 | 状态 |
|---|---|---|
| 后端 IT | `mvn -o test -pl notification4j-it` | 109/109 |
| 前端单测 | `pnpm vitest run` | 358/358 |
| 前端类型/lint | `pnpm vue-tsc -b --noEmit && pnpm eslint "src/**/*.vue"` | 0 错误 |

详见 `documents/接口设计文档.md`（V1.2.1 实现状态矩阵）与 `documents/adr/`。
