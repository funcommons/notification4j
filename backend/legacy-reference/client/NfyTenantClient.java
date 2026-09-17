package fun.commons.notification4j.client;

public interface NfyTenantClient {
    // 创建租户级自定义权益项
    Object postNfyItems(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostNfyItemsRequest req);

    // 分页查询租户权益项库
    Object getNfyItems(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId);

    // 查询特定权益项详情
    Object getNfyItemsItemId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("item_id") String itemId);

    // 修改指定权益项信息
    Object putNfyItemsItemId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("item_id") String itemId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PutNfyItemsItemIdRequest req);

    // 逻辑作废/删除指定权益项
    Object deleteNfyItemsItemId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("item_id") String itemId);

    // 查询系统授权给本租户的可用模板
    Object getNfyTemplates(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId);

    // 组合权益项创建/实例化售卖产品
    Object postNfySets(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostNfySetsRequest req);

    // 分页查询租户上架的权益产品
    Object getNfySets(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId);

    // 查询权益产品装配明细与额度
    Object getNfySetsSetId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("set_id") String setId);

    // 修改权益产品信息及额度配置
    Object putNfySetsSetId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("set_id") String setId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PutNfySetsSetIdRequest req);

    // 逻辑下架/删除指定权益产品
    Object deleteNfySetsSetId(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("set_id") String setId);

    // (客服能力) 穿透查阅指定用户资产大账
    Object getUsersUseridAssets(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("userid") String userid);

    // (客服能力) 穿透查询流水与订单关联
    Object getUsersUseridConsumes(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("userid") String userid);

    // (客服能力) 手工补发/开通权益产品
    Object postSubscriptions(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostSubscriptionsRequest req);

    // (客服能力) 手工封禁/停用作恶用户订阅
    Object postSubscriptionsSubscribeIdDisable(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.PathVariable("subscribe_id") String subscribeId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostSubscriptionsSubscribeIdDisableRequest req);

    // (客服能力) 人工补偿针对单项的额度
    Object postCompensations(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId, @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostCompensationsRequest req);

    // 多条件分页查询本租户用户订阅
    Object getSubscriptions(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId,
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

    // 列出指定订阅下的多源额度桶 (V1.2.0)
    Object getSubscriptionsSubscribeIdItems(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId,
                                            @org.springframework.web.bind.annotation.PathVariable("subscribe_id") String subscribeId,
                                            @org.springframework.web.bind.annotation.RequestParam(value = "item_id", required = false) String itemId);

    // 多条件分页查询本租户扣减流水
    Object getConsumes(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId,
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

    // 手动退减已 COMMIT 的扣减流水
    Object postConsumesIdRefund(@org.springframework.web.bind.annotation.RequestHeader(value="X-App-Id", required=false) Long tenantId,
                                @org.springframework.web.bind.annotation.PathVariable("consume_id") String consumeId,
                                @org.springframework.web.bind.annotation.RequestBody fun.commons.notification4j.dto.PostConsumesIdRefundRequest req);

}