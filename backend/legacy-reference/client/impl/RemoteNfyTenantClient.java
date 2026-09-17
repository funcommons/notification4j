package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyTenantClient;
import fun.commons.notification4j.dto.*;
import fun.commons.notification4j.properties.NfyProperties;
import fun.commons.framework4j.transport.HttpTransport;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * remote 模式 tenant client (跨进程调远端 notification4j tenant 域)。
 */
public class RemoteNfyTenantClient extends AbstractRemoteNfyClient implements NfyTenantClient {

    public RemoteNfyTenantClient(NfyProperties properties, HttpTransport transport) {
        super(properties, transport);
    }

    @Override
    public Object postNfyItems(Long tenantId, PostNfyItemsRequest req) {
        return invoke("/benefit/api/v1/tenant/benefit-items", "POST", tenantId, req);
    }

    @Override
    public Object getNfyItems(Long tenantId) {
        return invoke("/benefit/api/v1/tenant/benefit-items", "GET", tenantId, null);
    }

    @Override
    public Object getNfyItemsItemId(Long tenantId, String itemId) {
        return invoke("/benefit/api/v1/tenant/benefit-items/" + itemId, "GET", tenantId, null);
    }

    @Override
    public Object putNfyItemsItemId(Long tenantId, String itemId, PutNfyItemsItemIdRequest req) {
        return invoke("/benefit/api/v1/tenant/benefit-items/" + itemId, "PUT", tenantId, req);
    }

    @Override
    public Object deleteNfyItemsItemId(Long tenantId, String itemId) {
        return invoke("/benefit/api/v1/tenant/benefit-items/" + itemId, "DELETE", tenantId, null);
    }

    @Override
    public Object getNfyTemplates(Long tenantId) {
        return invoke("/benefit/api/v1/tenant/benefit-templates", "GET", tenantId, null);
    }

    @Override
    public Object postNfySets(Long tenantId, PostNfySetsRequest req) {
        return invoke("/benefit/api/v1/tenant/benefit-sets", "POST", tenantId, req);
    }

    @Override
    public Object getNfySets(Long tenantId) {
        return invoke("/benefit/api/v1/tenant/benefit-sets", "GET", tenantId, null);
    }

    @Override
    public Object getNfySetsSetId(Long tenantId, String setId) {
        return invoke("/benefit/api/v1/tenant/benefit-sets/" + setId, "GET", tenantId, null);
    }

    @Override
    public Object putNfySetsSetId(Long tenantId, String setId, PutNfySetsSetIdRequest req) {
        return invoke("/benefit/api/v1/tenant/benefit-sets/" + setId, "PUT", tenantId, req);
    }

    @Override
    public Object deleteNfySetsSetId(Long tenantId, String setId) {
        return invoke("/benefit/api/v1/tenant/benefit-sets/" + setId, "DELETE", tenantId, null);
    }

    @Override
    public Object getUsersUseridAssets(Long tenantId, String userid) {
        return invoke("/benefit/api/v1/tenant/users/" + userid + "/assets", "GET", tenantId, null);
    }

    @Override
    public Object getUsersUseridConsumes(Long tenantId, String userid) {
        return invoke("/benefit/api/v1/tenant/users/" + userid + "/consumes", "GET", tenantId, null);
    }

    @Override
    public Object postSubscriptions(Long tenantId, PostSubscriptionsRequest req) {
        return invoke("/benefit/api/v1/tenant/subscriptions", "POST", tenantId, req);
    }

    @Override
    public Object postSubscriptionsSubscribeIdDisable(Long tenantId, String subscribeId, PostSubscriptionsSubscribeIdDisableRequest req) {
        return invoke("/benefit/api/v1/tenant/subscriptions/" + subscribeId + "/disable", "POST", tenantId, req);
    }

    @Override
    public Object postCompensations(Long tenantId, PostCompensationsRequest req) {
        return invoke("/benefit/api/v1/tenant/compensations", "POST", tenantId, req);
    }

    @Override
    public Object getSubscriptions(Long tenantId, String userid, String setId, String status,
                                   String externalOrderId, String keyword,
                                   OffsetDateTime dateBeginStart, OffsetDateTime dateBeginEnd,
                                   OffsetDateTime createdAtStart, OffsetDateTime createdAtEnd,
                                   Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("userid", userid); q.put("set_id", setId); q.put("status", status);
        q.put("external_order_id", externalOrderId); q.put("keyword", keyword);
        q.put("date_begin_start", toStr(dateBeginStart)); q.put("date_begin_end", toStr(dateBeginEnd));
        q.put("created_at_start", toStr(createdAtStart)); q.put("created_at_end", toStr(createdAtEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/tenant/subscriptions", "GET", tenantId, null, q);
    }

    @Override
    public Object getSubscriptionsSubscribeIdItems(Long tenantId, String subscribeId, String itemId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("item_id", itemId);
        return invoke("/benefit/api/v1/tenant/subscriptions/" + subscribeId + "/items", "GET", tenantId, null, q);
    }

    @Override
    public Object getConsumes(Long tenantId, String userid, String subsItemId, String itemId, String status,
                              String externalOrderId, String keyword, Integer consumeNumMin, Integer consumeNumMax,
                              OffsetDateTime consumeTimeStart, OffsetDateTime consumeTimeEnd,
                              Integer page, Integer size) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("userid", userid); q.put("subs_item_id", subsItemId); q.put("item_id", itemId);
        q.put("status", status); q.put("external_order_id", externalOrderId); q.put("keyword", keyword);
        q.put("consume_num_min", toStr(consumeNumMin)); q.put("consume_num_max", toStr(consumeNumMax));
        q.put("consume_time_start", toStr(consumeTimeStart)); q.put("consume_time_end", toStr(consumeTimeEnd));
        q.put("page", toStr(page)); q.put("size", toStr(size));
        return invoke("/benefit/api/v1/tenant/consumes", "GET", tenantId, null, q);
    }

    @Override
    public Object postConsumesIdRefund(Long tenantId, String consumeId, PostConsumesIdRefundRequest req) {
        return invoke("/benefit/api/v1/tenant/consumes/" + consumeId + "/refund", "POST", tenantId, req);
    }

    private static String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
