package fun.commons.notification4j.autoconfigure;

import fun.commons.notification4j.properties.NfyProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * notification4j 自动装配。
 *
 * <p>装配排序：{@code after = MybatisPlusAutoConfiguration} —— 本类的
 * {@code @ConditionalOnBean(SqlSessionFactory.class)} 依赖 MyBatis-Plus 先注册 SqlSessionFactory 定义（mapper 由 @MapperScan 的延迟注册器提供，条件评估时定义尚不存在，不能作闸）
 * （评审第 2 步 P2：无序时仅靠类名字典序偶然成立，属未定义行为）。
 *
 * <p>mapper 注册归属：接入方以 {@code @MapperScan("fun.commons.notification4j.mapper")}
 * 扫描（同基包应用可依赖 @Mapper 自动扫描）；未扫描 mapper 时本装配的消息域 Bean 全部回退。
 *
 * <p>双模式：{@code nfy.runtime.enable-api=true} 时注册 admin/types + runtime/messages、channels、
 * subscriptions、announcements 控制器（auth 端点由 framework4j-tenant 的 tenant.auth.enabled 提供，
 * 独立部署形态）——嵌套于 NfyDataConfig 内：data 闸关闭时 API 一并缺席（见下方 warn Bean）；
 * 嵌入形态（enable-api=false，默认）仅装配 local service/remote client。
 *
 * <p>签名密钥装配排序（D-2 修复）：{@code before = SignatureAutoConfiguration}（fwk4j-signature，
 * 字符串形式避免编译依赖）——否则类名字典序 fwk4j 先行，其 InMemorySecretProvider 兜底先注册，
 * 本项目 provider 的条件判断让位 → 签名调用全拒 10200（NfySignatureFaceTest 活体复现）。
 */
@AutoConfiguration(after = com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class)
@AutoConfigureBefore(name = "fun.commons.framework4j.signature.config.SignatureAutoConfiguration")
@EnableConfigurationProperties(NfyProperties.class)
public class NfyAutoConfiguration {

    // ==== Remote Mode（跨进程调用：业务方配 nfy.runtime.mode=remote + remote-url + remote-tenant-id）====
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "nfy.runtime", name = "remote-tenant-id")
    public fun.commons.framework4j.transport.HttpTransport nfyAuthenticatedHttpTransport(
            org.springframework.beans.factory.ObjectProvider<org.springframework.web.client.RestTemplate> restTemplateProvider,
            org.springframework.beans.factory.ObjectProvider<fun.commons.framework4j.accesstoken.core.AccessTokenGenerator> tokenGeneratorProvider,
            NfyProperties properties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        // 业务方可能同时存在自有 RestTemplate 与 framework4j-transport 兜底 Bean → 取唯一者，多个则退回新建
        org.springframework.web.client.RestTemplate restTemplate = restTemplateProvider.getIfUnique();
        if (restTemplate == null) {
            restTemplate = new org.springframework.web.client.RestTemplate();
        }
        fun.commons.framework4j.transport.RestTemplateHttpTransport delegate =
                new fun.commons.framework4j.transport.RestTemplateHttpTransport(restTemplate);
        return new fun.commons.notification4j.transport.AuthenticatedHttpTransport(
                delegate, tokenGeneratorProvider, properties, objectMapper);
    }

    // ==== starter 语义装配（业务方基包不同也能生效；@ConditionalOnMissingBean 允许业务方覆盖，
    //      同时兼容同基包场景下组件扫描已注册的情形）====
    @Bean
    @ConditionalOnMissingBean(fun.commons.notification4j.controller.NfyExceptionHandler.class)
    public fun.commons.notification4j.controller.NfyExceptionHandler nfyExceptionHandler() {
        return new fun.commons.notification4j.controller.NfyExceptionHandler();
    }

    // ==== 业务方门面（编码第 8b 步）：nfy.runtime.client-enabled=true 显式开启；mode 分派 local/remote ====
    @Bean
    @ConditionalOnProperty(prefix = "nfy.runtime", name = "client-enabled", havingValue = "true")
    public fun.commons.notification4j.client.NotifyClient notifyClient(
            fun.commons.notification4j.properties.NfyProperties properties,
            org.springframework.beans.factory.ObjectProvider<fun.commons.notification4j.service.MessageService> messageService,
            org.springframework.beans.factory.ObjectProvider<fun.commons.notification4j.service.AnnouncementAdminService> announcementAdminService,
            org.springframework.beans.factory.ObjectProvider<fun.commons.notification4j.service.AnnouncementService> announcementService,
            org.springframework.beans.factory.ObjectProvider<fun.commons.framework4j.transport.HttpTransport> transport,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        if ("remote".equals(properties.getMode())) {
            // §5.3：remote 模式 remote-url 必填，缺失启动即失败
            org.springframework.util.Assert.hasText(properties.getRemoteUrl(),
                    "nfy.runtime.remote-url 必填（mode=remote）");
            fun.commons.framework4j.transport.HttpTransport t = transport.getIfAvailable();
            org.springframework.util.Assert.notNull(t,
                    "remote 模式需要 HttpTransport（AuthenticatedHttpTransport，检查 transport 依赖与配置）");
            return new fun.commons.notification4j.client.RemoteNotifyClient(t, properties, objectMapper);
        }
        // local：直连 service；数据面缺失启动即失败（§5.1「缺失启动即失败」）
        fun.commons.notification4j.service.MessageService m = messageService.getIfAvailable();
        org.springframework.util.Assert.notNull(m,
                "local 门面需要数据面：nfy.data.enabled=true 且扫描 fun.commons.notification4j.mapper");
        org.springframework.util.Assert.notNull(announcementAdminService.getIfAvailable(),
                "local 门面需要 AnnouncementAdminService");
        org.springframework.util.Assert.notNull(announcementService.getIfAvailable(),
                "local 门面需要 AnnouncementService");
        return new fun.commons.notification4j.client.LocalNotifyClient(
                m, announcementAdminService.getIfAvailable(), announcementService.getIfAvailable());
    }

    // ==== TraceLog（framework4j-tracelog）：控制台 API 鉴权，仅 OPS token 可用；业务方声明同名 Bean 即可覆盖 ====
    @Bean("traceLogAuthValidator")
    @ConditionalOnProperty(prefix = "framework4j.tracelog", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(fun.commons.framework4j.tracelog.config.TraceLogAuthValidator.class)
    public fun.commons.framework4j.tracelog.config.TraceLogAuthValidator nfyTraceLogAuthValidator(
            org.springframework.beans.factory.ObjectProvider<fun.commons.framework4j.accesstoken.config.AccessTokenProperties> accessTokenPropertiesProvider) {
        return new fun.commons.notification4j.tracelog.NfyTraceLogAuthValidator(accessTokenPropertiesProvider);
    }

    // ==== framework4j-tenant：实体子类 SPI（契约层冻结字段，表名 = {table-prefix}tenant = nfya_tenant）====
    @Bean
    @ConditionalOnMissingBean
    public fun.commons.framework4j.tenant.schema.TenantSchema tenantSchema() {
        return () -> fun.commons.notification4j.entity.NfyaTenant.class;
    }

    // ==== 配置矛盾告警（评审第 3 步 P2）：data 闸关闭而 enable-api 打开时 API 静默 404，启动期显式提示 ====
    @Bean
    @ConditionalOnProperty(prefix = "nfy.data", name = "enabled", havingValue = "false", matchIfMissing = false)
    public org.springframework.boot.ApplicationRunner nfyApiGateMismatchWarner(
            org.springframework.core.env.Environment env) {
        return args -> {
            if (env.getProperty("nfy.runtime.enable-api", Boolean.class, false)) {
                // 嵌入模式选 API off 属常见组合，降级为 warn 提示而非 fail-fast
                // (无法在此处用 log 对象以外的途径判断是否误配, 交由运维按提示处置)
                org.slf4j.LoggerFactory.getLogger(NfyAutoConfiguration.class)
                        .warn("[nfy] nfy.data.enabled=false 且 nfy.runtime.enable-api=true："
                                + "消息/渠道/订阅/公告 API 将全部 404（无 MyBatis 数据面）。如非有意为之请开启 nfy.data.enabled");
            }
        };
    }

    // ==== 数据依赖装配（编码第 2/3/4 步）：SqlSessionFactory 存在 + nfy.data.enabled 双闸 ====
    // mapper 由 @MapperScan 的延迟注册器提供, 条件评估时定义尚不存在, 不能作正向闸；
    // 无 MyBatis 场景(如纯 TCK 合规测试)以 nfy.data.enabled=false 显式关闭。
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(org.apache.ibatis.session.SqlSessionFactory.class)
    @ConditionalOnProperty(prefix = "nfy.data", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class NfyDataConfig {

        // D-2 修复：不加 @ConditionalOnMissingBean —— 本 provider 即 notification4j 确定性实现
        // （tenant 密钥来自 nfya_tenant），必须先于 fwk4j 兜底（InMemorySecretProvider）注册；
        // 类上 @AutoConfigureBefore(SignatureAutoConfiguration) 保证排序，业务方同名 Bean 仍可覆盖。
        @Bean
        public fun.commons.notification4j.kms.NfyTenantSecretProvider nfyTenantSecretProvider(
                fun.commons.notification4j.mapper.NfyaTenantMapper mapper) {
            return new fun.commons.notification4j.kms.NfyTenantSecretProvider(mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.MessageTypeService messageTypeService(
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.MessageTypeService(objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.MessageService messageService(
                fun.commons.notification4j.mapper.NfyaMessageRecipientMapper recipientMapper,
                fun.commons.notification4j.service.MessageTypeService messageTypeService,
                fun.commons.notification4j.service.DeliveryPlanService deliveryPlanService,
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper) {
            return new fun.commons.notification4j.service.MessageService(recipientMapper, messageTypeService, deliveryPlanService, deliveryMapper);
        }

        // ==== 渠道域（编码第 4 步）====

        /** SSRF 的 DNS 解析口：默认真实解析；业务方/测试声明同名 Bean 覆盖 */
        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.WebhookTargetResolver webhookTargetResolver() {
            return java.net.InetAddress::getAllByName;
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.WebhookTargetValidator webhookTargetValidator(
                fun.commons.notification4j.service.WebhookTargetResolver resolver) {
            return new fun.commons.notification4j.service.WebhookTargetValidator(resolver);
        }

        /**
         * 渠道验证器：外呼 RestTemplate 内建（5s 超时），不对外暴露 RestTemplate Bean——
         * 避免污染宿主应用按类型注入与 framework4j-transport 的候选集（评审第 4 步 P1）；
         * 测试经 channelVerifier.restTemplate() 绑定 MockRestServiceServer。
         */
        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.ChannelVerifier channelVerifier(
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.ChannelVerifier(
                    fun.commons.notification4j.service.ChannelVerifier.timeoutRestTemplate(), objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.SubscriptionService subscriptionService(
                fun.commons.notification4j.mapper.NfyaSubscriptionMapper subscriptionMapper,
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.service.MessageTypeService messageTypeService,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.SubscriptionService(
                    subscriptionMapper, channelMapper, messageTypeService, objectMapper);
        }

        /**
         * 渠道域公共核心（评审第 10~16 步 P2-1）：USER/TENANT 两面 register/verify/patch/
         * owner 校验的 scope 参数化共享实现，channelService/channelAdminService 均委托之。
         */
        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.ChannelCoreService channelCoreService(
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.service.WebhookTargetValidator targetValidator,
                fun.commons.notification4j.service.ChannelVerifier channelVerifier) {
            return new fun.commons.notification4j.service.ChannelCoreService(
                    channelMapper, targetValidator, channelVerifier);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.ChannelService channelService(
                fun.commons.notification4j.service.ChannelCoreService channelCoreService,
                fun.commons.notification4j.service.SubscriptionService subscriptionService) {
            return new fun.commons.notification4j.service.ChannelService(channelCoreService, subscriptionService);
        }

        // ==== 公告域 runtime 面（编码第 5a 步）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.AnnouncementService announcementService(
                fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper readMapper) {
            return new fun.commons.notification4j.service.AnnouncementService(readMapper);
        }

        // ==== 公告域管理面（编码第 5b 步）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.AnnouncementAdminService announcementAdminService(
                fun.commons.notification4j.mapper.NfyaAnnouncementMapper announcementMapper,
                fun.commons.notification4j.mapper.NfyaAnnouncementReadMapper readMapper,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                fun.commons.notification4j.service.DeliveryPlanService deliveryPlanService) {
            return new fun.commons.notification4j.service.AnnouncementAdminService(
                    announcementMapper, readMapper, objectMapper, deliveryPlanService);
        }

        // ==== 投递计划与查询（编码第 6a 步）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.DeliveryPlanService deliveryPlanService(
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.mapper.NfyaSubscriptionMapper subscriptionMapper,
                fun.commons.notification4j.mapper.NfyaMessageTypeMapper messageTypeMapper,
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.DeliveryPlanService(
                    channelMapper, subscriptionMapper, messageTypeMapper, deliveryMapper, objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.DeliveryAdminService deliveryAdminService(
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper) {
            return new fun.commons.notification4j.service.DeliveryAdminService(deliveryMapper);
        }

        // ==== 平台域（编码第 8a 步）：PTE-005 强制订阅 + PAN 平台公告 ====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.PlatformTypeService platformTypeService(
                fun.commons.notification4j.mapper.NfyaMessageTypeMapper messageTypeMapper) {
            return new fun.commons.notification4j.service.PlatformTypeService(messageTypeMapper);
        }

        // ==== 公共渠道管理面（编码第 10 步：ACH-001~003）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.ChannelAdminService channelAdminService(
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.service.ChannelCoreService channelCoreService) {
            return new fun.commons.notification4j.service.ChannelAdminService(channelMapper, channelCoreService);
        }

        // ==== 签名密钥管理（编码第 11 步：SEC-001）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.SignatureKeyService signatureKeyService(
                fun.commons.notification4j.mapper.NfyaTenantMapper tenantMapper) {
            return new fun.commons.notification4j.service.SignatureKeyService(tenantMapper);
        }

        // ==== 租户生命周期（编码第 12 步：PTE-001~004）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.PlatformTenantService platformTenantService(
                fun.commons.notification4j.mapper.NfyaTenantMapper tenantMapper,
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                org.springframework.beans.factory.ObjectProvider<fun.commons.framework4j.tenant.auth.TenantSessionRevoker> sessionRevoker) {
            return new fun.commons.notification4j.service.PlatformTenantService(
                    tenantMapper, deliveryMapper, objectMapper, sessionRevoker);
        }

        // ==== 注册码闭环（编码第 13 步：PRK-001 + OPEN-001）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.RegistrationKeyService registrationKeyService(
                fun.commons.notification4j.mapper.NfypRegistrationKeyMapper keyMapper,
                fun.commons.notification4j.mapper.NfyaTenantMapper tenantMapper,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.RegistrationKeyService(keyMapper, tenantMapper, objectMapper);
        }

        // ==== 统计概览（编码第 14 步：STAT-001 + PST-001）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.StatsService statsService(
                fun.commons.notification4j.mapper.NfyaMessageMapper messageMapper,
                fun.commons.notification4j.mapper.NfyaMessageRecipientMapper recipientMapper,
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper,
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.mapper.NfyaTenantMapper tenantMapper) {
            return new fun.commons.notification4j.service.StatsService(
                    messageMapper, recipientMapper, deliveryMapper, channelMapper, tenantMapper);
        }

        // ==== 平台站内信查询（API-PPM：平台域只读面，跨租户）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.PlatformMessageService platformMessageService(
                fun.commons.notification4j.service.MessageService messageService,
                fun.commons.notification4j.mapper.NfyaMessageRecipientMapper recipientMapper) {
            return new fun.commons.notification4j.service.PlatformMessageService(messageService, recipientMapper);
        }

        // ==== 批量发送 Job（编码第 15 步：MSG-002 + JOB-001，V1.1 提前落地）====

        @Bean
        @ConditionalOnBean(org.springframework.data.redis.core.StringRedisTemplate.class)
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.BatchJobService batchJobService(
                @org.springframework.beans.factory.annotation.Qualifier("stringRedisTemplate")
                org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                fun.commons.notification4j.service.MessageService messageService) {
            return new fun.commons.notification4j.service.BatchJobService(redisTemplate, objectMapper, messageService);
        }

        // ==== 模板域（编码第 16 步：TPL-001/002/003，V1.1 提前落地）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.TemplateService templateService(
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.TemplateService(objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.service.OemService oemService(
                fun.commons.notification4j.mapper.NfyaTenantMapper tenantMapper,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new fun.commons.notification4j.service.OemService(tenantMapper, objectMapper);
        }

        // ==== 外发引擎（编码第 6b 步）：nfy.runtime.engine.enabled 开关（SmartLifecycle 判定）====

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.engine.DeliveryClaimService deliveryClaimService(
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper) {
            return new fun.commons.notification4j.engine.DeliveryClaimService(deliveryMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public fun.commons.notification4j.engine.DeliveryEngine deliveryEngine(
                fun.commons.notification4j.mapper.NfyaDeliveryMapper deliveryMapper,
                fun.commons.notification4j.mapper.NfyaChannelMapper channelMapper,
                fun.commons.notification4j.mapper.NfyaMessageMapper messageMapper,
                fun.commons.notification4j.mapper.NfyaMessageRecipientMapper recipientMapper,
                fun.commons.notification4j.engine.DeliveryClaimService claimService,
                fun.commons.notification4j.properties.NfyProperties properties,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                org.springframework.beans.factory.ObjectProvider<fun.commons.notification4j.engine.ChannelSender> customSenders) {
            // 内建适配器：IM 三类型共用（5s 超时模板，非 Bean 不污染宿主装配）+ EMAIL（SMTP）
            fun.commons.notification4j.engine.ImWebhookSender im = new fun.commons.notification4j.engine.ImWebhookSender(
                    fun.commons.notification4j.service.ChannelVerifier.timeoutRestTemplate(), objectMapper);
            fun.commons.notification4j.engine.EmailSender email =
                    new fun.commons.notification4j.engine.EmailSender(properties.getEngine());
            return new fun.commons.notification4j.engine.DeliveryEngine(
                    deliveryMapper, channelMapper, messageMapper, recipientMapper,
                    claimService, properties, java.util.List.of(im, email), customSenders);
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "nfy.runtime", name = "enable-api", havingValue = "true")
        static class NfyMessageApiConfig {
            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminTypeController nfyAdminTypeController(
                    fun.commons.notification4j.service.MessageTypeService service) {
                return new fun.commons.notification4j.controller.NfyAdminTypeController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeMessageController nfyRuntimeMessageController(
                    fun.commons.notification4j.service.MessageService messageService,
                    fun.commons.notification4j.service.AnnouncementService announcementService,
                    fun.commons.notification4j.service.BatchJobService batchJobService) {
                return new fun.commons.notification4j.controller.NfyRuntimeMessageController(
                        messageService, announcementService, batchJobService);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeChannelController nfyRuntimeChannelController(
                    fun.commons.notification4j.service.ChannelService service) {
                return new fun.commons.notification4j.controller.NfyRuntimeChannelController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeSubscriptionController nfyRuntimeSubscriptionController(
                    fun.commons.notification4j.service.SubscriptionService service) {
                return new fun.commons.notification4j.controller.NfyRuntimeSubscriptionController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeAnnouncementController nfyRuntimeAnnouncementController(
                    fun.commons.notification4j.service.AnnouncementService service) {
                return new fun.commons.notification4j.controller.NfyRuntimeAnnouncementController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminAnnouncementController nfyAdminAnnouncementController(
                    fun.commons.notification4j.service.AnnouncementAdminService service) {
                return new fun.commons.notification4j.controller.NfyAdminAnnouncementController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminDeliveryController nfyAdminDeliveryController(
                    fun.commons.notification4j.service.DeliveryAdminService service) {
                return new fun.commons.notification4j.controller.NfyAdminDeliveryController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformAnnouncementController nfyPlatformAnnouncementController(
                    fun.commons.notification4j.service.AnnouncementAdminService service) {
                return new fun.commons.notification4j.controller.NfyPlatformAnnouncementController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformTypeController nfyPlatformTypeController(
                    fun.commons.notification4j.service.PlatformTypeService service) {
                return new fun.commons.notification4j.controller.NfyPlatformTypeController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformMessageController nfyPlatformMessageController(
                    fun.commons.notification4j.service.PlatformMessageService service) {
                return new fun.commons.notification4j.controller.NfyPlatformMessageController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminChannelController nfyAdminChannelController(
                    fun.commons.notification4j.service.ChannelAdminService service) {
                return new fun.commons.notification4j.controller.NfyAdminChannelController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminSignatureKeyController nfyAdminSignatureKeyController(
                    fun.commons.notification4j.service.SignatureKeyService service) {
                return new fun.commons.notification4j.controller.NfyAdminSignatureKeyController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformTenantController nfyPlatformTenantController(
                    fun.commons.notification4j.service.PlatformTenantService service) {
                return new fun.commons.notification4j.controller.NfyPlatformTenantController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyOpenRegisterController nfyOpenRegisterController(
                    fun.commons.notification4j.service.RegistrationKeyService service) {
                return new fun.commons.notification4j.controller.NfyOpenRegisterController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformRegistrationKeyController nfyPlatformRegistrationKeyController(
                    fun.commons.notification4j.service.RegistrationKeyService service) {
                return new fun.commons.notification4j.controller.NfyPlatformRegistrationKeyController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeQueryController nfyRuntimeQueryController(
                    fun.commons.notification4j.service.MessageService service) {
                return new fun.commons.notification4j.controller.NfyRuntimeQueryController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyRuntimeOemController nfyRuntimeOemController(
                    fun.commons.notification4j.service.OemService oemService) {
                return new fun.commons.notification4j.controller.NfyRuntimeOemController(oemService);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyJobController nfyJobController(
                    fun.commons.notification4j.service.BatchJobService service) {
                return new fun.commons.notification4j.controller.NfyJobController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminTemplateController nfyAdminTemplateController(
                    fun.commons.notification4j.service.TemplateService service) {
                return new fun.commons.notification4j.controller.NfyAdminTemplateController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyOpsHealthController nfyOpsHealthController(
                    org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory,
                    org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.connection.RedisConnectionFactory> redisFactory) {
                return new fun.commons.notification4j.controller.NfyOpsHealthController(
                        new fun.commons.notification4j.controller.NfyOpsHealthController.DataSourceHealth(sqlSessionFactory),
                        redisFactory);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyAdminStatsController nfyAdminStatsController(
                    fun.commons.notification4j.service.StatsService service) {
                return new fun.commons.notification4j.controller.NfyAdminStatsController(service);
            }

            @Bean
            @ConditionalOnMissingBean
            public fun.commons.notification4j.controller.NfyPlatformStatsController nfyPlatformStatsController(
                    fun.commons.notification4j.service.StatsService service) {
                return new fun.commons.notification4j.controller.NfyPlatformStatsController(service);
            }
        }
    }
}
