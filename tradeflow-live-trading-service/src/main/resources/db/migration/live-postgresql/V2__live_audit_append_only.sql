-- PostgreSQL only: the live audit log cannot be changed or deleted, even by the application's user.
CREATE OR REPLACE FUNCTION tradeflow_forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER live_audit_log_append_only
    BEFORE UPDATE OR DELETE ON live_audit_log
    FOR EACH ROW EXECUTE FUNCTION tradeflow_forbid_change();
