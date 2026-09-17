package fun.commons.notification4j.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.framework4j.accesstoken.core.AccessTokenGenerator;
import fun.commons.framework4j.transport.HttpTransport;
import fun.commons.framework4j.transport.RestTemplateHttpTransport;
import fun.commons.notification4j.client.NotifyClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * notification4j 轻量接入自动装配（编码第 30 步，部署形态三·跨进程 remote）。
 *
 * <p>只交付两个 Bean：{@link HttpTransport} 装饰器（{@link AuthenticatedHttpTransport}：
 * S2S JWT + HMAC 四元组头）与 {@link NotifyClient}（{@link RemoteNotifyClient}）。
 * 零 MyBatis/数据面/控制器/引擎——业务方跨进程调独立部署 notification4j。
 *
 * <p>装配条件与次序（互斥契约，见 README 部署节）：
 * <ul>
 *   <li>{@code nfy.runtime.client-enabled=true} 才装配（与全量 starter 同键：yml 切换零改动）。</li>
 *   <li>{@code @AutoConfigureAfter(name = NfyAutoConfiguration)}（字符串形式，不对全量 starter 编译依赖）：
 *       两个 starter 意外同 classpath 时全量 starter 的定义先注册，本装配两个
 *       {@code @ConditionalOnMissingBean} 确定性让位——无 classloader 先者胜的静默错配
 *       （API 类四件同 FQCN 且字节形态一致，实现类独立包名，互不链接）。共存仅浪费依赖体积，
 *       行为仍正确；共存且本装配被启用时由 {@link CoexistenceWarner} 输出 WARN 提示移除其一。
 *       不做启动 fail-fast 的理由：fail-fast 只能基于 classpath 共存判定，而测试模块
 *       （notification4j-it 同时引两个 starter 属合法编排）会被误伤。</li>
 * </ul>
 *
 * <p>降级路径（token 生成器 optional）：framework4j-accesstoken 类缺席 →
 * {@link HmacOnlyTransportConfig}；类在而 Bean 缺席 / generate 抛错 → 仅 HMAC 签名
 * （与全量 starter 第 26 步 P1 后口径一致：未配租户凭据不签名不 NPE）。
 *
 * <p>注意：accesstoken 引用仅存在于 {@link JwtAwareTransportConfig} 嵌套类（其
 * {@code @ConditionalOnClass} 经 ASM 评估，条件不满足时不加载 accesstoken 类），
 * 本外部类及其余实现类零硬引用——业务方不引 framework4j-accesstoken 亦可正常装配。
 */
@AutoConfiguration
@AutoConfigureAfter(name = "fun.commons.notification4j.autoconfigure.NfyAutoConfiguration")
@ConditionalOnProperty(prefix = "nfy.runtime", name = "client-enabled", havingValue = "true")
@EnableConfigurationProperties(NfyClientProperties.class)
public class ClientAutoConfiguration {

    // ==== HttpTransport 装饰器：accesstoken 类在 → S2S JWT + HMAC 双通道 ====
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AccessTokenGenerator.class)
    static class JwtAwareTransportConfig {

        @Bean
        @ConditionalOnMissingBean(HttpTransport.class)
        public HttpTransport nfyClientHttpTransport(ObjectProvider<RestTemplate> restTemplateProvider,
                                                    ObjectProvider<AccessTokenGenerator> tokenGeneratorProvider,
                                                    NfyClientProperties properties,
                                                    ObjectProvider<ObjectMapper> objectMapper) {
            AccessTokenGenerator generator = tokenGeneratorProvider.getIfAvailable();
            // Bean 缺席 → null supplier（仅 HMAC 签名降级路径，与全量 starter 口径一致）
            ServiceTokenSupplier supplier = generator == null ? null
                    : tenantId -> generator.generateToken("SERVICE", java.util.Map.of("tenant_id", tenantId));
            return new AuthenticatedHttpTransport(
                    new RestTemplateHttpTransport(resolveRestTemplate(restTemplateProvider, properties)),
                    supplier, properties, objectMapper(objectMapper));
        }
    }

    // ==== HttpTransport 装饰器：accesstoken 类缺席 → 仅 HMAC 签名（轻量默认形态） ====
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("fun.commons.framework4j.accesstoken.core.AccessTokenGenerator")
    static class HmacOnlyTransportConfig {

        @Bean
        @ConditionalOnMissingBean(HttpTransport.class)
        public HttpTransport nfyClientHttpTransport(ObjectProvider<RestTemplate> restTemplateProvider,
                                                    NfyClientProperties properties,
                                                    ObjectProvider<ObjectMapper> objectMapper) {
            return new AuthenticatedHttpTransport(
                    new RestTemplateHttpTransport(resolveRestTemplate(restTemplateProvider, properties)),
                    null, properties, objectMapper(objectMapper));
        }
    }

    // ==== 业务方门面：@ConditionalOnMissingBean 允许业务方自定义 NotifyClient 让位 ====
    @Bean
    @ConditionalOnMissingBean(NotifyClient.class)
    public NotifyClient notifyClient(NfyClientProperties properties,
                                     ObjectProvider<HttpTransport> transport,
                                     ObjectProvider<ObjectMapper> objectMapper) {
        org.springframework.util.Assert.state("remote".equals(properties.getMode()),
                "nfy.runtime.mode=local 不受 notification4j-client-starter 支持（无数据面），请改用全量 notification4j-starter");
        // §5.3 口径：remote 模式 remote-url 必填，缺失启动即失败
        org.springframework.util.Assert.hasText(properties.getRemoteUrl(),
                "nfy.runtime.remote-url 必填（notification4j-client-starter 仅支持 remote 模式）");
        HttpTransport t = transport.getIfAvailable();
        org.springframework.util.Assert.notNull(t,
                "remote 模式需要 HttpTransport（AuthenticatedHttpTransport，检查 transport 依赖与配置）");
        return new RemoteNotifyClient(t, properties, objectMapper(objectMapper));
    }

    /**
     * ObjectMapper：宿主 Bean（JacksonAutoConfiguration）优先；极端精简应用（无 spring-boot-starter-json
     * 自动配置）回退新建——client 信封解析只依赖通用 Map 绑定，默认配置即正确。
     */
    private static ObjectMapper objectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper existing = provider.getIfAvailable();
        return existing != null ? existing : new ObjectMapper();
    }

    // ==== 共存告警（互斥契约的运行期可见化）：全量 starter 类在 + 本装配被启用 → WARN 提示移除其一 ====
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "fun.commons.notification4j.properties.NfyProperties")
    static class CoexistenceWarner {

        @Bean
        public org.springframework.boot.ApplicationRunner nfyClientStarterCoexistenceWarner() {
            return args -> org.slf4j.LoggerFactory.getLogger(ClientAutoConfiguration.class)
                    .warn("[nfy] 检测到全量 notification4j-starter 与 notification4j-client-starter 共存："
                            + "两者互斥（见 README 部署节），Bean 由全量 starter 提供、client-starter 已让位。"
                            + "轻量接入请移除全量 starter；否则请移除 client-starter。");
        }
    }

    /**
     * delegate RestTemplate 解析：业务方唯一 RestTemplate Bean 优先（不改写其超时）；
     * 缺席/多义则新建并按 properties 注入连接/读超时（remote 跨进程调用不设超时 = 挂死风险）。
     * 包内可见：NfyClientStarterUnitTest 直接断言超时接线。
     */
    static RestTemplate resolveRestTemplate(ObjectProvider<RestTemplate> restTemplateProvider,
                                            NfyClientProperties properties) {
        RestTemplate existing = restTemplateProvider.getIfUnique();
        if (existing != null) {
            return existing;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
