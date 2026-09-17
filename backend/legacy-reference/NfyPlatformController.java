package fun.commons.notification4j.controller;

import fun.commons.notification4j.client.NfyPlatformClient;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.openid.annotation.OpenId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequiresToken(value = "APP", type = "access")
@fun.commons.framework4j.tenant.annotation.PlatformDomain
public class NfyPlatformController {

    private final NfyPlatformClient client;

    @PostMapping("/benefit/api/v1/platform/tenants")
    public Object postTenants(
            @Valid @RequestBody fun.commons.notification4j.dto.PostTenantsRequest req) {
        return client.postTenants(req);
    }

    @GetMapping("/benefit/api/v1/platform/tenants")
    public Object getTenants() {
        return client.getTenants();
    }

    @PutMapping("/benefit/api/v1/platform/tenants/{tenant_id}")
    public Object putTenantsTenantId(
            @OpenId @PathVariable("tenant_id") Long tenantId,
            @Valid @RequestBody fun.commons.notification4j.dto.PutTenantsTenantIdRequest req) {
        return client.putTenantsTenantId(tenantId, req);
    }

    @PostMapping("/benefit/api/v1/platform/tenants/{tenant_id}/reset-secret")
    public Object postTenantsTenantIdSecret(
            @OpenId @PathVariable("tenant_id") Long tenantId) {
        return client.postTenantsTenantIdSecret(tenantId);
    }

    @GetMapping("/benefit/api/v1/platform/tenants/{tenant_id}/secret")
    public Object getTenantsTenantIdSecret(
            @OpenId @PathVariable("tenant_id") Long tenantId) {
        return client.getTenantsTenantIdSecret(tenantId);
    }

    @PostMapping("/benefit/api/v1/platform/global-templates")
    public Object postGlobalTemplates(
            @Valid @RequestBody fun.commons.notification4j.dto.PostGlobalTemplatesRequest req) {
        return client.postGlobalTemplates(req);
    }

    @GetMapping("/benefit/api/v1/platform/global-templates")
    public Object getGlobalTemplates() {
        return client.getGlobalTemplates();
    }

    @PutMapping("/benefit/api/v1/platform/global-templates/{tmpl_id}")
    public Object putGlobalTemplatesTmplId(
            @OpenId @PathVariable("tmpl_id") Long tmplId,
            @Valid @RequestBody fun.commons.notification4j.dto.PutGlobalTemplatesTmplIdRequest req) {
        return client.putGlobalTemplatesTmplId(tmplId, req);
    }

    @DeleteMapping("/benefit/api/v1/platform/global-templates/{tmpl_id}")
    public Object deleteGlobalTemplatesTmplId(@OpenId @PathVariable("tmpl_id") Long tmplId) {
        return client.deleteGlobalTemplatesTmplId(tmplId);
    }

    @GetMapping("/benefit/api/v1/platform/statistics/liabilities")
    public Object getStatisticsLiabilities() {
        return client.getStatisticsLiabilities();
    }

    @GetMapping("/benefit/api/v1/platform/items")
    public Object getPlatformItems(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
            @RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        return client.getPlatformItems(tenantId, status, keyword, createdAtStart, createdAtEnd, page, size);
    }

    @GetMapping("/benefit/api/v1/platform/benefit-sets")
    public Object getPlatformNfySets(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "priority_min", required = false) Integer priorityMin,
            @RequestParam(value = "priority_max", required = false) Integer priorityMax,
            @RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
            @RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        return client.getPlatformNfySets(tenantId, status, keyword, priorityMin, priorityMax, createdAtStart, createdAtEnd, page, size);
    }

    @PostMapping("/benefit/api/v1/platform/item-templates")
    public Object postItemTemplates(
            @Valid @RequestBody fun.commons.notification4j.dto.PostItemTemplatesRequest req) {
        return client.postItemTemplates(req);
    }

    @GetMapping("/benefit/api/v1/platform/item-templates")
    public Object getItemTemplates() {
        return client.getItemTemplates();
    }

    @PutMapping("/benefit/api/v1/platform/item-templates/{item_id}")
    public Object putItemTemplatesItemId(
            @OpenId @PathVariable("item_id") Long itemId,
            @Valid @RequestBody fun.commons.notification4j.dto.PutItemTemplatesItemIdRequest req) {
        return client.putItemTemplatesItemId(itemId, req);
    }

    @DeleteMapping("/benefit/api/v1/platform/item-templates/{item_id}")
    public Object deleteItemTemplatesItemId(@OpenId @PathVariable("item_id") Long itemId) {
        return client.deleteItemTemplatesItemId(itemId);
    }

    @GetMapping("/benefit/api/v1/platform/subscriptions")
    public Object getPlatformSubscriptions(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @RequestParam(value = "userid", required = false) String userid,
            @RequestParam(value = "set_id", required = false) String setId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "external_order_id", required = false) String externalOrderId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "date_begin_start", required = false) java.time.OffsetDateTime dateBeginStart,
            @RequestParam(value = "date_begin_end", required = false) java.time.OffsetDateTime dateBeginEnd,
            @RequestParam(value = "created_at_start", required = false) java.time.OffsetDateTime createdAtStart,
            @RequestParam(value = "created_at_end", required = false) java.time.OffsetDateTime createdAtEnd,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        return client.getPlatformSubscriptions(tenantId, userid, setId, status, externalOrderId, keyword, dateBeginStart, dateBeginEnd, createdAtStart, createdAtEnd, page, size);
    }

    @GetMapping("/benefit/api/v1/platform/subscriptions/{subscribe_id}/items")
    public Object getPlatformSubscriptionsSubscribeIdItems(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @PathVariable("subscribe_id") String subscribeId,
            @RequestParam(value = "item_id", required = false) String itemId) {
        return client.getPlatformSubscriptionsSubscribeIdItems(tenantId, subscribeId, itemId);
    }

    @GetMapping("/benefit/api/v1/platform/consumes")
    public Object getPlatformConsumes(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @RequestParam(value = "userid", required = false) String userid,
            @RequestParam(value = "subs_item_id", required = false) String subsItemId,
            @RequestParam(value = "item_id", required = false) String itemId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "external_order_id", required = false) String externalOrderId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "consume_num_min", required = false) Integer consumeNumMin,
            @RequestParam(value = "consume_num_max", required = false) Integer consumeNumMax,
            @RequestParam(value = "consume_time_start", required = false) java.time.OffsetDateTime consumeTimeStart,
            @RequestParam(value = "consume_time_end", required = false) java.time.OffsetDateTime consumeTimeEnd,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        return client.getPlatformConsumes(tenantId, userid, subsItemId, itemId, status, externalOrderId, keyword, consumeNumMin, consumeNumMax, consumeTimeStart, consumeTimeEnd, page, size);
    }

    @PostMapping("/benefit/api/v1/platform/consumes/{consume_id}/refund")
    public Object postPlatformConsumesIdRefund(
            @RequestParam(value = "tenant_id", required = false) Long tenantId,
            @PathVariable("consume_id") String consumeId,
            @Valid @RequestBody fun.commons.notification4j.dto.PostConsumesIdRefundRequest req) {
        return client.postPlatformConsumesIdRefund(tenantId, consumeId, req);
    }
}