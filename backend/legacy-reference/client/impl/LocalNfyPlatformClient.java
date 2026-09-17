package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyPlatformClient;
import fun.commons.notification4j.service.NfyPlatformService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LocalNfyPlatformClient implements NfyPlatformClient {

    private final NfyPlatformService service;

    @Override
    public Object postTenants(fun.commons.notification4j.dto.PostTenantsRequest req) {
        return service.postTenants(req);
    }

    @Override
    public Object getTenants() {
        return service.getTenants();
    }

    @Override
    public Object putTenantsTenantId(Long tenantId, fun.commons.notification4j.dto.PutTenantsTenantIdRequest req) {
        return service.putTenantsTenantId(tenantId, req);
    }

    @Override
    public Object postTenantsTenantIdSecret(Long tenantId) {
        return service.postTenantsTenantIdSecret(tenantId);
    }

    @Override
    public Object getTenantsTenantIdSecret(Long tenantId) {
        return service.getTenantsTenantIdSecret(tenantId);
    }

    @Override
    public Object postGlobalTemplates(fun.commons.notification4j.dto.PostGlobalTemplatesRequest req) {
        return service.postGlobalTemplates(req);
    }

    @Override
    public Object getGlobalTemplates() {
        return service.getGlobalTemplates();
    }

    @Override
    public Object putGlobalTemplatesTmplId(Long tmplId, fun.commons.notification4j.dto.PutGlobalTemplatesTmplIdRequest req) {
        return service.putGlobalTemplatesTmplId(tmplId, req);
    }

    @Override
    public Object deleteGlobalTemplatesTmplId(Long tmplId) {
        return service.deleteGlobalTemplatesTmplId(tmplId);
    }

    @Override
    public Object getStatisticsLiabilities() {
        return service.getStatisticsLiabilities();
    }

    @Override
    public Object getPlatformItems(Long tenantId, String status, String keyword, java.time.OffsetDateTime createdAtStart, java.time.OffsetDateTime createdAtEnd, Integer page, Integer size) {
        return service.getPlatformItems(tenantId, status, keyword, createdAtStart, createdAtEnd, page, size);
    }

    @Override
    public Object getPlatformNfySets(Long tenantId, String status, String keyword, Integer priorityMin, Integer priorityMax, java.time.OffsetDateTime createdAtStart, java.time.OffsetDateTime createdAtEnd, Integer page, Integer size) {
        return service.getPlatformNfySets(tenantId, status, keyword, priorityMin, priorityMax, createdAtStart, createdAtEnd, page, size);
    }

    @Override
    public Object postItemTemplates(fun.commons.notification4j.dto.PostItemTemplatesRequest req) {
        return service.postItemTemplates(req);
    }

    @Override
    public Object getItemTemplates() {
        return service.getItemTemplates();
    }

    @Override
    public Object putItemTemplatesItemId(Long itemId, fun.commons.notification4j.dto.PutItemTemplatesItemIdRequest req) {
        return service.putItemTemplatesItemId(itemId, req);
    }

    @Override
    public Object deleteItemTemplatesItemId(Long itemId) {
        return service.deleteItemTemplatesItemId(itemId);
    }

    @Override
    public Object getPlatformSubscriptions(Long tenantId, String userid, String setId, String status, String externalOrderId, String keyword, java.time.OffsetDateTime dateBeginStart, java.time.OffsetDateTime dateBeginEnd, java.time.OffsetDateTime createdAtStart, java.time.OffsetDateTime createdAtEnd, Integer page, Integer size) {
        return service.getPlatformSubscriptions(tenantId, userid, setId, status, externalOrderId, keyword, dateBeginStart, dateBeginEnd, createdAtStart, createdAtEnd, page, size);
    }

    @Override
    public Object getPlatformConsumes(Long tenantId, String userid, String subsItemId, String itemId, String status, String externalOrderId, String keyword, Integer consumeNumMin, Integer consumeNumMax, java.time.OffsetDateTime consumeTimeStart, java.time.OffsetDateTime consumeTimeEnd, Integer page, Integer size) {
        return service.getPlatformConsumes(tenantId, userid, subsItemId, itemId, status, externalOrderId, keyword, consumeNumMin, consumeNumMax, consumeTimeStart, consumeTimeEnd, page, size);
    }

    @Override
    public Object postPlatformConsumesIdRefund(Long tenantId, String consumeId, fun.commons.notification4j.dto.PostConsumesIdRefundRequest req) {
        return service.postPlatformConsumesIdRefund(tenantId, consumeId, req);
    }

    @Override
    public Object getPlatformSubscriptionsSubscribeIdItems(Long tenantId, String subscribeId, String itemId) {
        return service.getPlatformSubscriptionsSubscribeIdItems(tenantId, subscribeId, itemId);
    }

}