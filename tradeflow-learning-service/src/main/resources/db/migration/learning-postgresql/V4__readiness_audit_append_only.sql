-- PostgreSQL only (Flyway location db/migration/learning-{vendor}): the readiness audit log and the
-- risk acknowledgements are append-only at the database level. Any UPDATE or DELETE fails, even
-- from the application's own database user. (The hash chain additionally makes tampering detectable.)
CREATE OR REPLACE FUNCTION tradeflow_forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER readiness_events_append_only
    BEFORE UPDATE OR DELETE ON readiness_events
    FOR EACH ROW EXECUTE FUNCTION tradeflow_forbid_change();

CREATE TRIGGER risk_acknowledgements_append_only
    BEFORE UPDATE OR DELETE ON risk_acknowledgements
    FOR EACH ROW EXECUTE FUNCTION tradeflow_forbid_change();
