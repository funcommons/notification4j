package fun.commons.notification4j.controller;

import fun.commons.notification4j.client.NfyTenantClient;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.id.util.IdObfuscator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequiresToken(value = "APP", type = "access")
@fun.commons.framework4j.tenant.annotation.TenantDomain
public class NfyTenantController {


    private final NfyTenantClient client;

    @PostMapping("/benefit/api/v1/tenant/benefit-items")
    public Object postNfyItems(
            @Valid @RequestBody fun.commons.notification4j.dto.PostNfyItemsRequest req) {
        return client.postNfyItems(tenantId(), req);
    }

    @GetMapping("/benefit/api/v1/tenant/benefit-items")
    public Object getNfyItems() {
        return client.getNfyItems(tenantId());
    }

    @GetMapping("/benefit/api/v1/tenant/benefit-items/{item_id}")
    public Object getNfyItemsItemId(
            @PathVariable("item_id") String itemId) {
        return client.getNfyItemsItemId(tenantId(), itemId);
    }

    @PutMapping("/benefit/api/v1/tenant/benefit-items/{item_id}")
    public Object putNfyItemsItemId(
            @PathVariable("item_id") String itemId,
            @Valid @RequestBody fun.commons.notification4j.dto.PutNfyItemsItemIdRequest req) {
        return client.putNfyItemsItemId(tenantId(), itemId, req);
    }

    @DeleteMapping("/benefit/api/v1/tenant/benefit-items/{item_id}")
    public Object deleteNfyItemsItemId(
            @PathVariable("item_id") String itemId) {
        return client.deleteNfyItemsItemId(tenantId(), itemId);
    }

    @GetMapping("/benefit/api/v1/tenant/benefit-templates")
    public Object getNfyTemplates() {
        return client.getNfyTemplates(tenantId());
    }

    @PostMapping("/benefit/api/v1/tenant/benefit-sets")
    public Object postNfySets(
            @Valid @RequestBody fun.commons.notification4j.dto.PostNfySetsRequest req) {
        return client.postNfySets(tenantId(), req);
    }

    @GetMapping("/benefit/api/v1/tenant/benefit-sets")
    public Object getNfySets() {
        return client.getNfySets(tenantId());
    }

    @GetMapping("/benefit/api/v1/tenant/benefit-sets/{set_id}")
    public Object getNfySetsSetId(
            @PathVariable("set_id") String setId) {
        return client.getNfySetsSetId(tenantId(), setId);
    }

    @PutMapping("/benefit/api/v1/tenant/benefit-sets/{set_id}")
    public Object putNfySetsSetId(
            @PathVariable("set_id") String setId,
            @Valid @RequestBody fun.commons.notification4j.dto.PutNfySetsSetIdRequest req) {
        return client.putNfySetsSetId(tenantId(), setId, req);
    }

    @DeleteMapping("/benefit/api/v1/tenant/benefit-sets/{set_id}")
    public Object deleteNfySetsSetId(
            @PathVariable("set_id") String setId) {
        return client.deleteNfySetsSetId(tenantId(), setId);
    }

    @GetMapping("/benefit/api/v1/tenant/users/{userid}/assets")
    public Object getUsersUseridAssets(
            @PathVariable("userid") String userid) {
        return client.getUsersUseridAssets(tenantId(), userid);
    }

    @GetMapping("/benefit/api/v1/tenant/users/{userid}/consumes")
    public Object getUsersUseridConsumes(
            @PathVariable("userid") String userid) {
        return client.getUsersUseridConsumes(tenantId(), userid);
    }

    @PostMapping("/benefit/api/v1/tenant/subscriptions")
    public Object postSubscriptions(
            @Valid @RequestBody fun.commons.notification4j.dto.PostSubscriptionsRequest req) {
        return client.postSubscriptions(tenantId(), req);
    }

    /** 批量发放订阅 (运营活动 N 用户一次, 循环 + 幂等 external_order_id) */
    @PostMapping("/benefit/api/v1/tenant/subscriptions/batch")
    public Object postSubscriptionsBatch(
            @RequestBody java.util.List<fun.commons.notification4j.dto.PostSubscriptionsRequest> requests) {
        Long tenantId = tenantId();
        java.util.List<Object> results = new java.util.ArrayList<>();
        int success = 0, failed = 0;
        for (fun.commons.notification4j.dto.PostSubscriptionsRequest req : requests) {
            try {
                results.add(client.postSubscriptions(tenantId, req));
                success++;
            } catch (Exception e) {
                java.util.Map<String, Object> err = new java.util.LinkedHashMap<>();
                err.put("external_order_id", req.getExternalOrderId());
                err.put("error", e.getMessage());
                results.add(err);
                failed++;
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", requests.size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("results", results);
        return fun.commons.framework4j.web.ApiResponse.success(result);
    }

    @PostMapping("/benefit/api/v1/tenant/subscriptions/{subscribe_id}/disable")
    public Object postSubscriptionsSubscribeIdDisable(
            @PathVariable("subscribe_id") String subscribeId,
            @Valid @RequestBody fun.commons.notification4j.dto.PostSubscriptionsSubscribeIdDisableRequest req) {
        return client.postSubscriptionsSubscribeIdDisable(tenantId(), subscribeId, req);
    }

    @GetMapping("/benefit/api/v1/tenant/subscriptions/{subscribe_id}/items")
    public Object getSubscriptionsSubscribeIdItems(
            @PathVariable("subscribe_id") String subscribeId,
            @RequestParam(value = "item_id", required = false) String itemId) {
        return client.getSubscriptionsSubscribeIdItems(tenantId(), subscribeId, itemId);
    }

    @PostMapping("/benefit/api/v1/tenant/compensations")
    public Object postCompensations(
            @Valid @RequestBody fun.commons.notification4j.dto.PostCompensationsRequest req) {
        return client.postCompensations(tenantId(), req);
    }

    @GetMapping("/benefit/api/v1/tenant/subscriptions")
    public Object getSubscriptions(
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
        return client.getSubscriptions(tenantId(), userid, setId, status, externalOrderId, keyword, dateBeginStart, dateBeginEnd, createdAtStart, createdAtEnd, page, size);
    }

    @GetMapping("/benefit/api/v1/tenant/consumes")
    public Object getConsumes(
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
        return client.getConsumes(tenantId(), userid, subsItemId, itemId, status, externalOrderId, keyword, consumeNumMin, consumeNumMax, consumeTimeStart, consumeTimeEnd, page, size);
    }

    @PostMapping("/benefit/api/v1/tenant/consumes/{consume_id}/refund")
    public Object postConsumesIdRefund(
            @PathVariable("consume_id") String consumeId,
            @Valid @RequestBody fun.commons.notification4j.dto.PostConsumesIdRefundRequest req) {
        return client.postConsumesIdRefund(tenantId(), consumeId, req);
    }

    private Long tenantId() {
        Object claim = TokenContext.getClaim("tenant_id");
        if (claim == null) return null;
        if (claim instanceof Long l) return l;
        if (claim instanceof Number n) return n.longValue();
        return IdObfuscator.fromOpenId(String.valueOf(claim));
    }
}