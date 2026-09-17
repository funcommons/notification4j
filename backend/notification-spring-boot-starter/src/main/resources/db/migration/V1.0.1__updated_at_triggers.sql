-- V1.0.1: updated_at 自动维护（评审第 5 步 P1-4）
-- 背景：MyBatis-Plus 无 MetaObjectHandler，updateById 会把加载时的旧 updated_at 原样写回，
-- 导致 updated_at 冻结在插入时刻（DBD 表 4「随已读变更」/表 6「确认时更新」未兑现，
-- 管理面 updated_at 倒序退化为 created_at）。以 DB 触发器全表兜底，对 ORM 零侵入。

CREATE OR REPLACE FUNCTION nfy_set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'nfya_tenant', 'nfya_message_type', 'nfya_message', 'nfya_message_recipient',
        'nfya_announcement', 'nfya_announcement_read', 'nfya_channel', 'nfya_subscription',
        'nfya_template', 'nfya_delivery', 'nfyp_registration_key'
    ] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_updated_at ON %I', t, t);
        EXECUTE format(
            'CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION nfy_set_updated_at()',
            t, t);
    END LOOP;
END $$;
