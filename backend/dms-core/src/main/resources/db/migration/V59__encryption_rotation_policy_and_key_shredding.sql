-- Master-key rotation schedule per tenant, and crypto-shredding of a removed
-- tenant's keys (Module DE, DE-FR-005).
--
-- encryption_key_policies: how often a tenant's master key should be
-- rotated, and whether dms-core does it by itself or only reminds. No row
-- means the defaults (dms.encryption.rotation.*). last_rotated_at is written
-- on every rotation, by a person or the schedule.
--
-- tenants.keys_shredded_at: set once a platform admin has destroyed the
-- tenant's master key and its wrapped data keys. From then on everything the
-- tenant stored encrypted is unreadable for good, so the tenant can't be
-- reactivated either.

CREATE TABLE encryption_key_policies (
    tenant_id               UUID         PRIMARY KEY REFERENCES tenants (tenant_id),
    -- NULL turns the schedule off for this tenant.
    rotation_interval_days  INTEGER,
    auto_rotate             BOOLEAN      NOT NULL DEFAULT FALSE,
    last_rotated_at         TIMESTAMPTZ,
    last_reminded_at        TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              UUID,
    CONSTRAINT ck_encryption_key_policies_interval
        CHECK (rotation_interval_days IS NULL OR rotation_interval_days BETWEEN 30 AND 3650)
);

ALTER TABLE tenants ADD COLUMN keys_shredded_at TIMESTAMPTZ;
