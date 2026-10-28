-- 4.3 Pin Important Documents (PID).
--
--   document_pins  one row per (user, document): that user's own pinned documents (PID-FR-006).
--                  Pins are personal - they never change what anyone else sees or may open
--                  (PID-NFR-003); the pinned list is read through the same access rules as the
--                  document browser, so a pin cannot widen anyone's access.
--   pin_settings   how many documents one user may pin, per organisation (PID-FR-007). No row =
--                  the platform default (dms.pins.default-max).
--
-- Documents deleted for good take their pins with them (ON DELETE CASCADE). A document that is only
-- in the recycle bin, or one the user can no longer see (PID-FR-008/009), is dropped from the list
-- by the service when the pinned section is next read - the rule lives in one place (the access
-- rules), not in a trigger that would have to follow every way access can change.

CREATE TABLE document_pins (
    tenant_id    UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    document_id  UUID        NOT NULL,
    pinned_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_id),
    CONSTRAINT fk_document_pins_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE
);

-- The pinned section: one user's pins, newest first. Bounded by the limit, so it reads a handful of
-- rows however many documents are pinned across the system (PID-NFR-005).
CREATE INDEX ix_document_pins_user ON document_pins (tenant_id, user_id, pinned_at DESC);

-- Removing a document's pins when it is purged.
CREATE INDEX ix_document_pins_document ON document_pins (tenant_id, document_id);

CREATE TABLE pin_settings (
    tenant_id   UUID        PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    max_pins    INTEGER     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID,
    CONSTRAINT ck_pin_settings_max CHECK (max_pins BETWEEN 1 AND 200)
);
