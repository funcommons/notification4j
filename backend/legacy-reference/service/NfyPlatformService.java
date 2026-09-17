package fun.commons.notification4j.service;

public interface NfyPlatformService {
    // 颁发新租户(开通 TenantId、AK、SK)
    Object postTenants(fun.commons.notification4j.dto.PostTenantsRequest req);

    // 全局租户管控列表查询
    Object getTenants();

    // 修改租户基础信息或封禁租户
    Object putTenantsTenantId(Long tenantId, fun.commons.notification4j.dto.PutTenantsTenantIdRequest req);

    // 重置租户密钥 (返回新明文密钥, 仅出现一次)
    Object postTenantsTenantIdSecret(Long tenantId);

    // 揭示租户密钥明文 (列表接口脱敏, 仅在此处返回完整密钥)
    Object getTenantsTenantIdSecret(Long tenantId);

    // 沉淀并发布系统全局通用权益模板
    Object postGlobalTemplates(fun.commons.notification4j.dto.PostGlobalTemplatesRequest req);

    // 查询系统所有已发布全局模板
    Object getGlobalTemplates();

    // 修改全局通用权益模板
    Object putGlobalTemplatesTmplId(Long tmplId, fun.commons.notification4j.dto.PutGlobalTemplatesTmplIdRequest req);

    // 作废下架全局模板
    Object deleteGlobalTemplatesTmplId(Long tmplId);

    // 查询全平台权益滞留总负债报表
    Object getStatisticsLiabilities();

    // 平台权益项总览: 跨租户权益项实时聚合 (包含 quota / used / usage), 多条件分页只读
    Object getPlatformItems(Long tenantId,
                            String status,
                            String keyword,
                            java.time.OffsetDateTime createdAtStart,
                            java.time.OffsetDateTime createdAtEnd,
                            Integer page,
                            Integer size);

    // 平台权益包总览: 跨租户权益包实时聚合 (租户nfya_benefit_set, 非模板), 多条件分页只读
    Object getPlatformNfySets(Long tenantId,
                                  String status,
                                  String keyword,
                                  Integer priorityMin,
                                  Integer priorityMax,
                                  java.time.OffsetDateTime createdAtStart,
                                  java.time.OffsetDateTime createdAtEnd,
                                  Integer page,
                                  Integer size);

    // 平台级权益项模板 CRUD
    Object postItemTemplates(fun.commons.notification4j.dto.PostItemTemplatesRequest req);

    Object getItemTemplates();

    Object putItemTemplatesItemId(Long itemId, fun.commons.notification4j.dto.PutItemTemplatesItemIdRequest req);

    Object deleteItemTemplatesItemId(Long itemId);

    // 跨租户多条件分页查询订阅
    Object getPlatformSubscriptions(Long tenantId,
                                    String userid,
                                    String setId,
                                    String status,
                                    String externalOrderId,
                                    String keyword,
                                    java.time.OffsetDateTime dateBeginStart,
                                    java.time.OffsetDateTime dateBeginEnd,
                                    java.time.OffsetDateTime createdAtStart,
                                    java.time.OffsetDateTime createdAtEnd,
                                    Integer page,
                                    Integer size);

    // 跨租户列出指定订阅下的多源额度桶 (V1.2.0)
    Object getPlatformSubscriptionsSubscribeIdItems(Long tenantId, String subscribeId, String itemId);

    // 跨租户多条件分页查询扣减流水
    Object getPlatformConsumes(Long tenantId,
                               String userid,
                               String subsItemId,
                               String itemId,
                               String status,
                               String externalOrderId,
                               String keyword,
                               Integer consumeNumMin,
                               Integer consumeNumMax,
                               java.time.OffsetDateTime consumeTimeStart,
                               java.time.OffsetDateTime consumeTimeEnd,
                               Integer page,
                               Integer size);

    // 跨租户手动退减扣减流水
    Object postPlatformConsumesIdRefund(Long tenantId, String consumeId, fun.commons.notification4j.dto.PostConsumesIdRefundRequest req);

}