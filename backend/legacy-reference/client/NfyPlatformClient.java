package fun.commons.notification4j.client;

public interface NfyPlatformClient {
    // 颁发新租户(开通 TenantId、AK、SK)
    Object postTenants(@org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostTenantsRequest req);

    // 全局租户管控列表查询
    Object getTenants();

    // 修改租户基础信息或封禁租户
    Object putTenantsTenantId(@org.springframework.web.bind.annotation.PathVariable("tenant_id") Long tenantId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PutTenantsTenantIdRequest req);

    // 重置应用密钥 (返回新明文密钥, 仅出现一次)
    Object postTenantsTenantIdSecret(@org.springframework.web.bind.annotation.PathVariable("tenant_id") Long tenantId);

    // 揭示应用密钥明文 (仅在用户主动点击查看时调用)
    Object getTenantsTenantIdSecret(@org.springframework.web.bind.annotation.PathVariable("tenant_id") Long tenantId);

    // 沉淀并发布系统全局通用权益模板
    Object postGlobalTemplates(@org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostGlobalTemplatesRequest req);

    // 查询系统所有已发布全局模板
    Object getGlobalTemplates();

    // 修改全局通用权益模板
    Object putGlobalTemplatesTmplId(@org.springframework.web.bind.annotation.PathVariable("tmpl_id") Long tmplId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PutGlobalTemplatesTmplIdRequest req);

    // 作废下架全局模板
    Object deleteGlobalTemplatesTmplId(@org.springframework.web.bind.annotation.PathVariable("tmpl_id") Long tmplId);

    // 查询全平台权益滞留总负债报表
    Object getStatisticsLiabilities();

    // 平台权益项总览 (跨租户, 含 quota/used/usage_pct), 多条件分页只读
    Object getPlatformItems(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                            @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
                            @org.springframework.web.bind.annotation.RequestParam(value = "keyword", required = false) String keyword,
                            @org.springframework.web.bind.annotation.RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
                            @org.springframework.web.bind.annotation.RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
                            @org.springframework.web.bind.annotation.RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                            @org.springframework.web.bind.annotation.RequestParam(value = "size", required = false, defaultValue = "20") Integer size);

    // 平台权益包总览 (跨租户聚合 nfya_benefit_set), 多条件分页只读
    Object getPlatformNfySets(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "keyword", required = false) String keyword,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "priority_min", required = false) Integer priorityMin,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "priority_max", required = false) Integer priorityMax,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                  @org.springframework.web.bind.annotation.RequestParam(value = "size", required = false, defaultValue = "20") Integer size);

    // 平台级权益项模板 CRUD
    Object postItemTemplates(@org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostItemTemplatesRequest req);

    Object getItemTemplates();

    Object putItemTemplatesItemId(@org.springframework.web.bind.annotation.PathVariable("item_id") Long itemId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PutItemTemplatesItemIdRequest req);

    Object deleteItemTemplatesItemId(@org.springframework.web.bind.annotation.PathVariable("item_id") Long itemId);

    // 跨租户多条件分页查询订阅
    Object getPlatformSubscriptions(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "userid", required = false) String userid,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "set_id", required = false) String setId,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "external_order_id", required = false) String externalOrderId,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "keyword", required = false) String keyword,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "date_begin_start", required = false) java.time.OffsetDateTime dateBeginStart,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "date_begin_end", required = false) java.time.OffsetDateTime dateBeginEnd,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                    @org.springframework.web.bind.annotation.RequestParam(value = "size", required = false, defaultValue = "20") Integer size);

    // 跨租户列出指定订阅下的多源额度桶 (V1.2.0)
    Object getPlatformSubscriptionsSubscribeIdItems(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                                                    @org.springframework.web.bind.annotation.PathVariable("subscribe_id") String subscribeId,
                                                    @org.springframework.web.bind.annotation.RequestParam(value = "item_id", required = false) String itemId);

    // 跨租户多条件分页查询扣减流水
    Object getPlatformConsumes(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                               @org.springframework.web.bind.annotation.RequestParam(value = "userid", required = false) String userid,
                               @org.springframework.web.bind.annotation.RequestParam(value = "subs_item_id", required = false) String subsItemId,
                               @org.springframework.web.bind.annotation.RequestParam(value = "item_id", required = false) String itemId,
                               @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
                               @org.springframework.web.bind.annotation.RequestParam(value = "external_order_id", required = false) String externalOrderId,
                               @org.springframework.web.bind.annotation.RequestParam(value = "keyword", required = false) String keyword,
                               @org.springframework.web.bind.annotation.RequestParam(value = "consume_num_min", required = false) Integer consumeNumMin,
                               @org.springframework.web.bind.annotation.RequestParam(value = "consume_num_max", required = false) Integer consumeNumMax,
                               @org.springframework.web.bind.annotation.RequestParam(value = "consume_time_start", required = false) java.time.OffsetDateTime consumeTimeStart,
                               @org.springframework.web.bind.annotation.RequestParam(value = "consume_time_end", required = false) java.time.OffsetDateTime consumeTimeEnd,
                               @org.springframework.web.bind.annotation.RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                               @org.springframework.web.bind.annotation.RequestParam(value = "size", required = false, defaultValue = "20") Integer size);

    // 跨租户手动退减扣减流水
    Object postPlatformConsumesIdRefund(@org.springframework.web.bind.annotation.RequestParam(value = "tenant_id", required = false) Long tenantId,
                                        @org.springframework.web.bind.annotation.PathVariable("consume_id") String consumeId,
                                        @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostConsumesIdRefundRequest req);

}