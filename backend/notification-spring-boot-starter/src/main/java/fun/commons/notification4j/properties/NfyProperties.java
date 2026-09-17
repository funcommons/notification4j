package fun.commons.notification4j.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Pattern;

@Data
@Validated
@ConfigurationProperties(prefix = "nfy.runtime")
public class NfyProperties {

    /**
     * 运行模式：local（本地进程内调用）或 remote（远程 HTTP 调用）
     */
    @Pattern(regexp = "^(local|remote)$", message = "mode 只能是 local 或 remote")
    private String mode = "local";

    /**
     * 是否启动内嵌的 OpenAPI Web 层路由
     */
    private boolean enableApi = false;

    /**
     * remote 模式下的远程服务端点，例如 http://notification4j-svc:8080
     */
    private String remoteUrl;

    /**
     * remote 模式下业务方 tenant_id (OpenID, 用于 S2S JWT claims + X-Access-Key)
     */
    private String remoteTenantId;

    /**
     * remote 模式下业务方 tenant_secret (HMAC-SHA256 签名密钥, 从独立部署 notification4j 平台获取)
     */
    private String remoteTenantSecret;

    /** 业务方门面开关（第 8b 步）：true 时装配 NotifyClient（local/remote 按 mode 分派；默认关） */
    private boolean clientEnabled = false;

    /** 外发引擎（第 6b 步）：nfy.engine.* 前缀 */
    private final Engine engine = new Engine();

    @Data
    public static class Engine {
        /** 引擎开关：默认关（嵌入方自带调度时显式开；独立部署 app.yml 开） */
        private boolean enabled = false;
        /** 扫描周期 ms */
        private long scanIntervalMs = 5000;
        /** 单批领取行数 */
        private int batchSize = 50;
        /** reaper 周期 ms（回收 SENDING 超时行） */
        private long reaperIntervalMs = 60000;
        /** SENDING 视为卡死的阈值 ms */
        private long sendingStaleMs = 600000;
        /** 重试退避秒（默认 1/5/15min，测试可缩短） */
        private String backoffSeconds = "60,300,900";
        /** 每渠道每分钟投递上限（内置固定窗；钉钉/企微硬限额 20/min，默认 18 留余量；0=不限） */
        private int rateLimitPerMinute = 18;
        /** 投递工作线程数（V1.0 跨渠道共享池；按渠道池隔离登记排期） */
        private int workerCount = 4;
        /** SMTP：host 为空时 EMAIL 渠道直接投递失败（走重试→DEAD） */
        private String mailHost = "";
        private int mailPort = 25;
        private String mailFrom = "";
        private String mailUsername = "";
        private String mailPassword = "";
    }
}
