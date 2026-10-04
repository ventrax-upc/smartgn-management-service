-- Preserve existing data and the already-applied V1 migration.
ALTER TABLE installations DROP CONSTRAINT installations_status_check;
ALTER TABLE installations DROP CONSTRAINT installations_check;
ALTER TABLE installations ADD CONSTRAINT installations_status_check
    CHECK (status IN ('PENDING','ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED'));
ALTER TABLE installations ADD CONSTRAINT installation_installer_required
    CHECK (status IN ('PENDING','CANCELLED') OR installer_id IS NOT NULL);
DROP INDEX installation_open_point_idx;
CREATE UNIQUE INDEX installation_open_point_idx ON installations(point_id)
    WHERE status NOT IN ('COMPLETED','CANCELLED');

-- Append-only tariff versions. Earlier overwritten rates cannot be reconstructed safely.
CREATE TABLE tariff_history (
    account_id uuid NOT NULL, price_per_m3 numeric(14,6) NOT NULL CHECK (price_per_m3 > 0),
    effective_from timestamptz NOT NULL, version bigint NOT NULL CHECK (version >= 0),
    PRIMARY KEY (account_id, version), UNIQUE (account_id, effective_from)
);
INSERT INTO tariff_history SELECT account_id,price_per_m3,effective_from,version FROM tariffs;
CREATE INDEX tariff_history_period_idx ON tariff_history(account_id,effective_from);
CREATE INDEX installations_property_status_idx ON installations(property_id,status);
CREATE INDEX devices_property_state_idx ON devices(property_id,status,broker_confirmed);
CREATE INDEX outbox_review_idx ON outbox_jobs(status,attempts,created_at,id);
