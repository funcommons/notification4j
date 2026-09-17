package fun.commons.notification4j.client.impl;

import fun.commons.notification4j.client.NfyTenantClient;
import fun.commons.notification4j.service.NfyTenantService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LocalNfyTenantClient implements NfyTenantClient {

    private final NfyTenantService service;

    @Override
    public Object postNfyItems(Long tenantId, fun.commons.notification4j.dto.PostNfyItemsRequest req) {
        return service.postNfyItems(tenantId, req);
    }

    @Override
    public Object getNfyItems(Long tenantId) {
        return service.getNfyItems(tenantId);
    }

    @Override
    public Object getNfyItemsItemId(Long tenantId, String itemId) {
        return service.getNfyItemsItemId(tenantId, itemId);
    }

    @Override
    public Object putNfyItemsItemId(Long tenantId, String itemId, fun.commons.notification4j.dto.PutNfyItemsItemIdRequest req) {
        return service.putNfyItemsItemId(tenantId, itemId, req);
    }

    @Override
    public Object deleteNfyItemsItemId(Long tenantId, String itemId) {
        return service.deleteNfyItemsItemId(tenantId, itemId);
    }

    @Override
    public Object getNfyTemplates(Long tenantId) {
        return service.getNfyTemplates(tenantId);
    }

    @Override
    public Object postNfySets(Long tenantId, fun.commons.notification4j.dto.PostNfySetsRequest req) {
        return service.postNfySets(tenantId, req);
    }

    @Override
    public Object getNfySets(Long tenantId) {
        return service.getNfySets(tenantId);
    }

    @Override
    public Object getNfySetsSetId(Long tenantId, String setId) {
        return service.getNfySetsSetId(tenantId, setId);
    }

    @Override
    public Object putNfySetsSetId(Long tenantId, String setId, fun.commons.notification4j.dto.PutNfySetsSetIdRequest req) {
        return service.putNfySetsSetId(tenantId, setId, req);
    }

    @Override
    public Object deleteNfySetsSetId(Long tenantId, String setId) {
        return service.deleteNfySetsSetId(tenantId, setId);
    }

    @Override
    public Object getUsersUseridAssets(Long tenantId, String userid) {
        return service.getUsersUseridAssets(tenantId, userid);
    }

    @Override
    public Object getUsersUseridConsumes(Long tenantId, String userid) {
        return service.getUsersUseridConsumes(tenantId, userid);
    }

    @Override
    public Object postSubscriptions(Long tenantId, fun.commons.notification4j.dto.PostSubscriptionsRequest req) {
        return service.postSubscriptions(tenantId, req);
    }

    @Override
    public Object postSubscriptionsSubscribeIdDisable(Long tenantId, String subscribeId, fun.commons.notification4j.dto.PostSubscriptionsSubscribeIdDisableRequest req) {
        return service.postSubscriptionsSubscribeIdDisable(tenantId, subscribeId, req);
    }

    @Override
    public Object postCompensations(Long tenantId, fun.commons.notification4j.dto.PostCompensationsRequest req) {
        return service.postCompensations(tenantId, req);
    }

    @Override
    public Object getSubscriptions(Long tenantId, String userid, String setId, String status, String externalOrderId, String keyword, java.time.OffsetDateTime dateBeginStart, java.time.OffsetDateTime dateBeginEnd, java.time.OffsetDateTime createdAtStart, java.time.OffsetDateTime createdAtEnd, Integer page, Integer size) {
        return service.getSubscriptions(tenantId, userid, setId, status, externalOrderId, keyword, dateBeginStart, dateBeginEnd, createdAtStart, createdAtEnd, page, size);
    }

    @Override
    public Object getConsumes(Long tenantId, String userid, String subsItemId, String itemId, String status, String externalOrderId, String keyword, Integer consumeNumMin, Integer consumeNumMax, java.time.OffsetDateTime consumeTimeStart, java.time.OffsetDateTime consumeTimeEnd, Integer page, Integer size) {
        return service.getConsumes(tenantId, userid, subsItemId, itemId, status, externalOrderId, keyword, consumeNumMin, consumeNumMax, consumeTimeStart, consumeTimeEnd, page, size);
    }

    @Override
    public Object postConsumesIdRefund(Long tenantId, String consumeId, fun.commons.notification4j.dto.PostConsumesIdRefundRequest req) {
        return service.postConsumesIdRefund(tenantId, consumeId, req);
    }

    @Override
    public Object getSubscriptionsSubscribeIdItems(Long tenantId, String subscribeId, String itemId) {
        return service.getSubscriptionsSubscribeIdItems(tenantId, subscribeId, itemId);
    }

}