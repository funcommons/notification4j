package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyPlatformClient;
import fun.commons.notification4j.dto.*;
import fun.commons.notification4j.properties.NfyProperties;
import fun.commons.framework4j.transport.HttpTransport;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * remote 模式 platform client (跨进程调远端 notification4j platform 域)。
 */
public class RemoteNfyPlatformClient extends AbstractRemoteNfyClient implements NfyPlatformClient {

    public RemoteNfyPlatformClient(NfyProperties properties, HttpTransport transport) {
        super(properties, transport);
    }

    @Override
    public Object postTenants(PostTenantsRequest req) {
        return invoke("/benefit/api/v1/platform/tenants", "POST", null, req);
    }

    @Override
    public Object getTenants() {
        return invoke("/benefit/api/v1/platform/tenants", "GET", null, null);
    }

    @Override
    public Object putTenantsTenantId(Long tenantId, PutTenantsTenantIdRequest req) {
        return invoke("/benefit/api/v1/platform/tenants/" + tenantId, "PUT", null, req);
    }

    @Override
    public Object postTenantsTenantIdSecret(Long tenantId) {
        return invoke("/benefit/api/v1/platform/tenants/" + tenantId + "/reset-secret", "POST", null, null);
    }

    @Override
    public Object getTenantsTenantIdSecret(Long tenantId) {
        return invoke("/benefit/api/v1/platform/tenants/" + tenantId + "/secret", "GET", null, null);
    }

    @Override
    public Object postGlobalTemplates(PostGlobalTemplatesRequest req) {
        return invoke("/benefit/api/v1/platform/global-templates", "POST", null, req);
    }

    @Override
    public Object getGlobalTemplates() {
        return invoke("/benefit/api/v1/platform/global-templates", "GET", null, null);
    }

    @Override
    public Object putGlobalTemplatesTmplId(Long tmplId, PutGlobalTemplatesTmplIdRequest req) {
        return invoke("/benefit/api/v1/platform/global-templates/" + tmplId, "PUT", null, req);
    }

    @Override
    public Object deleteGlobalTemplatesTmplId(Long tmplId) {
        return invoke("/benefit/api/v1/platform/global-templates/" + tmplId, "DELETE", null, null);
    }

    @Override
    public Object getStatisticsLiabilities() {
        return invoke("/benefit/api/v1/platform/statistics/liabilities", "GET", null, null);
    }

    @Override
    public Object getPlatformItems(Long tenantId, String status, String keyword,
                                   OffsetDateTime createdAtStart, OffsetDateTime createdAtEnd,
                                   Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId)); q.put("status", status); q.put("keyword", keyword);
        q.put("created_at_start", toStr(createdAtStart)); q.put("created_at_end", toStr(createdAtEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/platform/items", "GET", null, null, q);
    }

    @Override
    public Object getPlatformNfySets(Long tenantId, String status, String keyword,
                                         Integer priorityMin, Integer priorityMax,
                                         OffsetDateTime createdAtStart, OffsetDateTime createdAtEnd,
                                         Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId)); q.put("status", status); q.put("keyword", keyword);
        q.put("priority_min", toStr(priorityMin)); q.put("priority_max", toStr(priorityMax));
        q.put("created_at_start", toStr(createdAtStart)); q.put("created_at_end", toStr(createdAtEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/platform/benefit-sets", "GET", null, null, q);
    }

    @Override
    public Object postItemTemplates(PostItemTemplatesRequest req) {
        return invoke("/benefit/api/v1/platform/item-templates", "POST", null, req);
    }

    @Override
    public Object getItemTemplates() {
        return invoke("/benefit/api/v1/platform/item-templates", "GET", null, null);
    }

    @Override
    public Object putItemTemplatesItemId(Long itemId, PutItemTemplatesItemIdRequest req) {
        return invoke("/benefit/api/v1/platform/item-templates/" + itemId, "PUT", null, req);
    }

    @Override
    public Object deleteItemTemplatesItemId(Long itemId) {
        return invoke("/benefit/api/v1/platform/item-templates/" + itemId, "DELETE", null, null);
    }

    @Override
    public Object getPlatformSubscriptions(Long tenantId, String userid, String setId, String status,
                                          String externalOrderId, String keyword,
                                          OffsetDateTime dateBeginStart, OffsetDateTime dateBeginEnd,
                                          OffsetDateTime createdAtStart, OffsetDateTime createdAtEnd,
                                          Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId)); q.put("userid", userid); q.put("set_id", setId);
        q.put("status", status); q.put("external_order_id", externalOrderId); q.put("keyword", keyword);
        q.put("date_begin_start", toStr(dateBeginStart)); q.put("date_begin_end", toStr(dateBeginEnd));
        q.put("created_at_start", toStr(createdAtStart)); q.put("created_at_end", toStr(createdAtEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/platform/subscriptions", "GET", null, null, q);
    }

    @Override
    public Object getPlatformSubscriptionsSubscribeIdItems(Long tenantId, String subscribeId, String itemId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId)); q.put("item_id", itemId);
        return invoke("/benefit/api/v1/platform/subscriptions/" + subscribeId + "/items", "GET", null, null, q);
    }

    @Override
    public Object getPlatformConsumes(Long tenantId, String userid, String subsItemId, String itemId, String status,
                                      String externalOrderId, String keyword,
                                      Integer consumeNumMin, Integer consumeNumMax,
                                      OffsetDateTime consumeTimeStart, OffsetDateTime consumeTimeEnd,
                                      Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId)); q.put("userid", userid); q.put("subs_item_id", subsItemId);
        q.put("item_id", itemId); q.put("status", status); q.put("external_order_id", externalOrderId);
        q.put("keyword", keyword); q.put("consume_num_min", toStr(consumeNumMin)); q.put("consume_num_max", toStr(consumeNumMax));
        q.put("consume_time_start", toStr(consumeTimeStart)); q.put("consume_time_end", toStr(consumeTimeEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/platform/consumes", "GET", null, null, q);
    }

    @Override
    public Object postPlatformConsumesIdRefund(Long tenantId, String consumeId, PostConsumesIdRefundRequest req) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tenant_id", toStr(tenantId));
        return invoke("/benefit/api/v1/platform/consumes/" + consumeId + "/refund", "POST", null, req, q);
    }

    private static String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
