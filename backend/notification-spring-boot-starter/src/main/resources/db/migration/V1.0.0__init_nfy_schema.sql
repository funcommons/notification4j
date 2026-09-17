-- =============================================================================
-- NFY (Notification Middle-platform) Database Schema  — V1.0.0
-- 来源: documents/数据库设计文档.md §9.1（评审 4 轮定稿 V1.0.3）
-- 内容: nfya_* 业务表 10 张 + nfyp_registration_key + nfyp_audit_log(append-only, framework4j-audit 仅写入不建表)
-- 规约: 无外键/无触发器/无存储过程; 参与查询过滤的 JSONB 列建 GIN; 唯一约束部分唯一(is_deleted=0)
-- 契约: nfya_tenant 列集须与 framework4j-tenant TenantEntity/TenantDdlGenerator 冻结契约一致
--       (description/tenant_secret_prev_at 为契约列, 缺列将使任何 MP 查询报 column not exist)
-- =============================================================================

-- 1. nfya_tenant 租户主表
CREATE TABLE nfya_tenant (
    id bigint NOT NULL,
    name varchar(68) NOT NULL,
    description varchar(516) NOT NULL DEFAULT '',
    email varchar(132) NULL,
    channel varchar(20) NOT NULL DEFAULT 'OPS',
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    tenant_secret varchar(132) NOT NULL,
    tenant_secret_prev varchar(132) NOT NULL DEFAULT '',
    tenant_secret_prev_at timestamptz,
    privileges jsonb NOT NULL DEFAULT '{}'::jsonb,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    oem jsonb NOT NULL DEFAULT '{}'::jsonb,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_tenant_ext ON nfya_tenant USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_tenant_email ON nfya_tenant (email) WHERE (is_deleted = 0 AND email IS NOT NULL);

-- 2. nfya_message_type 消息类型
CREATE TABLE nfya_message_type (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    type_code varchar(36) NOT NULL,
    name varchar(68) NOT NULL,
    description varchar(516) NOT NULL DEFAULT '',
    default_level varchar(20) NOT NULL DEFAULT 'NORMAL',
    default_channels jsonb NOT NULL DEFAULT '["INAPP"]'::jsonb,
    mandatory smallint NOT NULL DEFAULT 0,
    built_in smallint NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'ENABLED',
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_message_type_tenant ON nfya_message_type (tenant_id);
CREATE INDEX idx_nfya_message_type_ext ON nfya_message_type USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_message_type_tenant_code ON nfya_message_type (tenant_id, type_code) WHERE (is_deleted = 0);

-- 3. nfya_message 消息主表
CREATE TABLE nfya_message (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    biz_no varchar(68) NOT NULL,
    type_code varchar(36) NOT NULL,
    level varchar(20) NOT NULL DEFAULT 'NORMAL',
    title varchar(132) NOT NULL,
    content text NOT NULL,
    link_url varchar(516) NOT NULL DEFAULT '',
    template_id bigint NOT NULL DEFAULT 0,
    params jsonb NOT NULL DEFAULT '{}'::jsonb,
    receiver_count integer NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'SENT',
    sender varchar(68) NOT NULL DEFAULT '',
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_message_tenant_time ON nfya_message (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_nfya_message_params ON nfya_message USING gin (params);
CREATE INDEX idx_nfya_message_ext ON nfya_message USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_message_tenant_biz_no ON nfya_message (tenant_id, biz_no) WHERE (is_deleted = 0);

-- 4. nfya_message_recipient 消息接收记录
CREATE TABLE nfya_message_recipient (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    message_id bigint NOT NULL,
    userid varchar(68) NOT NULL,
    read_status varchar(20) NOT NULL DEFAULT 'UNREAD',
    read_at timestamptz,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_recipient_user_time ON nfya_message_recipient (tenant_id, userid, created_at DESC, id DESC);
CREATE INDEX idx_nfya_recipient_unread ON nfya_message_recipient (tenant_id, userid) WHERE (read_status = 'UNREAD' AND is_deleted = 0);
CREATE UNIQUE INDEX uk_nfya_recipient_msg_user ON nfya_message_recipient (tenant_id, message_id, userid) WHERE (is_deleted = 0);

-- 5. nfya_announcement 公告
CREATE TABLE nfya_announcement (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    biz_no varchar(68) NOT NULL DEFAULT '',
    scope varchar(20) NOT NULL DEFAULT 'TENANT',
    title varchar(132) NOT NULL,
    content text NOT NULL,
    level varchar(20) NOT NULL DEFAULT 'IMPORTANT',
    need_confirm smallint NOT NULL DEFAULT 0,
    link_url varchar(516) NOT NULL DEFAULT '',
    channel_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    effective_at timestamptz NOT NULL,
    expire_at timestamptz NOT NULL,
    published_at timestamptz,
    confirm_count integer NOT NULL DEFAULT 0,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_ann_runtime ON nfya_announcement (tenant_id, status, expire_at);
CREATE INDEX idx_nfya_ann_tenant_time ON nfya_announcement (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_nfya_ann_channels ON nfya_announcement USING gin (channel_ids);
CREATE INDEX idx_nfya_ann_ext ON nfya_announcement USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_ann_tenant_biz_no ON nfya_announcement (tenant_id, biz_no) WHERE (is_deleted = 0 AND biz_no <> '');

-- 6. nfya_announcement_read 公告回执
CREATE TABLE nfya_announcement_read (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    announcement_id bigint NOT NULL,
    userid varchar(68) NOT NULL,
    read_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirm_at timestamptz,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_ann_read_ann ON nfya_announcement_read (announcement_id);
CREATE INDEX idx_nfya_ann_read_ext ON nfya_announcement_read USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_ann_read_user ON nfya_announcement_read (tenant_id, announcement_id, userid) WHERE (is_deleted = 0);

-- 7. nfya_channel 渠道
CREATE TABLE nfya_channel (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    scope varchar(20) NOT NULL DEFAULT 'USER',
    userid varchar(68) NOT NULL DEFAULT '',
    channel_type varchar(20) NOT NULL,
    name varchar(68) NOT NULL,
    target varchar(516) NOT NULL,
    secret varchar(259) NOT NULL DEFAULT '',
    keyword varchar(68) NOT NULL DEFAULT '',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    fail_count integer NOT NULL DEFAULT 0,
    last_verify_at timestamptz,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_channel_user ON nfya_channel (tenant_id, userid, status);
CREATE INDEX idx_nfya_channel_scope_type ON nfya_channel (tenant_id, scope, channel_type, status);
CREATE INDEX idx_nfya_channel_ext ON nfya_channel USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_channel_target ON nfya_channel (tenant_id, userid, channel_type, md5(target)) WHERE (is_deleted = 0);

-- 8. nfya_subscription 订阅偏好
CREATE TABLE nfya_subscription (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    userid varchar(68) NOT NULL,
    type_code varchar(36) NOT NULL,
    channel_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    quiet_hours jsonb NOT NULL DEFAULT '{}'::jsonb,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_subscription_channels ON nfya_subscription USING gin (channel_ids);
CREATE INDEX idx_nfya_subscription_ext ON nfya_subscription USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_subscription_user_type ON nfya_subscription (tenant_id, userid, type_code) WHERE (is_deleted = 0);

-- 9. nfya_template 消息模板(V1.1 启用, 表先建)
CREATE TABLE nfya_template (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    template_code varchar(68) NOT NULL,
    name varchar(68) NOT NULL,
    type_code varchar(36) NOT NULL,
    title_tpl varchar(259) NOT NULL,
    content_tpl text NOT NULL,
    channel_content jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'ENABLED',
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_template_tenant_type ON nfya_template (tenant_id, type_code);
CREATE INDEX idx_nfya_template_content ON nfya_template USING gin (channel_content);
CREATE INDEX idx_nfya_template_ext ON nfya_template USING gin (ext);
CREATE UNIQUE INDEX uk_nfya_template_code ON nfya_template (tenant_id, template_code) WHERE (is_deleted = 0);

-- 10. nfya_delivery 外发投递记录
CREATE TABLE nfya_delivery (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    source_type varchar(20) NOT NULL,
    source_id bigint NOT NULL,
    userid varchar(68) NOT NULL DEFAULT '',
    channel_id bigint NOT NULL,
    channel_type varchar(20) NOT NULL,
    target varchar(516) NOT NULL DEFAULT '',
    title varchar(132) NOT NULL DEFAULT '',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    retry_count integer NOT NULL DEFAULT 0,
    next_retry_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_message varchar(516) NOT NULL DEFAULT '',
    trace_id varchar(68) NOT NULL DEFAULT '',
    sent_at timestamptz,
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfya_delivery_pending ON nfya_delivery (next_retry_at) WHERE (status IN ('PENDING','FAILED'));
CREATE INDEX idx_nfya_delivery_sending ON nfya_delivery (updated_at) WHERE (status = 'SENDING');
CREATE INDEX idx_nfya_delivery_tenant_time ON nfya_delivery (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_nfya_delivery_source ON nfya_delivery (tenant_id, source_type, source_id);
CREATE INDEX idx_nfya_delivery_user ON nfya_delivery (tenant_id, userid, created_at);
CREATE UNIQUE INDEX uk_nfya_delivery_source ON nfya_delivery (tenant_id, source_type, source_id, userid, channel_id) WHERE (is_deleted = 0);

-- 11. nfyp_registration_key 注册码(平台层资源, 无 tenant_id; 消费后回填)
CREATE TABLE nfyp_registration_key (
    id bigint NOT NULL,
    code varchar(68) NOT NULL,
    max_uses integer NOT NULL DEFAULT 1,
    used_count integer NOT NULL DEFAULT 0,
    preset jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    expire_at timestamptz NOT NULL,
    consumed_tenant_id bigint NOT NULL DEFAULT 0,
    issue_by varchar(68) NOT NULL DEFAULT '',
    ext jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by varchar(68) NOT NULL DEFAULT '',
    update_by varchar(68) NOT NULL DEFAULT '',
    is_deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfyp_reg_key_ext ON nfyp_registration_key USING gin (ext);
CREATE UNIQUE INDEX uk_nfyp_reg_key_code ON nfyp_registration_key (code) WHERE (is_deleted = 0);

-- 12. nfyp_audit_log 审计日志(framework4j-audit JdbcAuditSink append-only 写入; 结构与 benefit4j ubmp_audit_log 同源)
CREATE TABLE nfyp_audit_log (
    id bigserial NOT NULL,
    action varchar(68) NOT NULL,
    target_type varchar(68) NOT NULL,
    target_id varchar(68),
    actor varchar(68),
    result varchar(20) NOT NULL,
    error_message text,
    args_json text,
    result_json text,
    ip varchar(49),
    user_agent varchar(259),
    trace_id varchar(68),
    timestamp timestamptz NOT NULL,
    prev_hash varchar(132) NOT NULL,
    hash varchar(132) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_nfyp_audit_actor_time ON nfyp_audit_log USING btree (actor, "timestamp" DESC);
CREATE INDEX idx_nfyp_audit_target ON nfyp_audit_log USING btree (target_type, target_id);
CREATE INDEX idx_nfyp_audit_time ON nfyp_audit_log USING btree ("timestamp" DESC);
CREATE UNIQUE INDEX uk_nfyp_audit_log_hash ON nfyp_audit_log USING btree (hash);
