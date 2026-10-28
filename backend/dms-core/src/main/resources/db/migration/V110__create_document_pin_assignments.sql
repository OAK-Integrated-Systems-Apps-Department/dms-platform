-- 4.3 Pin Important Documents (PID): a pin now has an audience.
--
-- Until now a pin was personal (document_pins: one row per user and document). Now the person who
-- pins a document chooses who sees it, so there is one kind of pin and no separate "pin for me":
--
--   document_pin_assignments       one row per (document, pinner): "this person pinned this document
--                                  for this audience". Only the pinner can change or remove it.
--   document_pin_audience_members  who the audience rule names. What the names MEAN depends on the
--                                  mode of the assignment:
--                                    USERS -> these users SEE the pin        (inclusions)
--                                    ALL   -> these users do NOT see it      (exclusions)
--
-- ALL is a rule, not a list of everyone, so a person who joins the organisation later sees the pin
-- without anyone touching it, and an organisation of thousands is not thousands of rows per pin.
--
-- A pin never grants access (PID-NFR-003): what a recipient sees is still filtered through the
-- document access rules when the list is read. That is also why a recipient losing access does not
-- delete anything here - the assignment belongs to the pinner.
--
-- Documents deleted for good take their assignments with them (ON DELETE CASCADE).

CREATE TABLE document_pin_assignments (
    assignment_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    document_id    UUID        NOT NULL,
    pinned_by      UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    audience_mode  VARCHAR(10) NOT NULL,
    pinned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pin_assignments_mode CHECK (audience_mode IN ('ALL', 'USERS')),
    CONSTRAINT uq_pin_assignments_pinner UNIQUE (tenant_id, document_id, pinned_by),
    CONSTRAINT fk_pin_assignments_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE
);

-- "My pins" and the per-person pin limit count.
CREATE INDEX ix_pin_assignments_pinner ON document_pin_assignments (tenant_id, pinned_by, pinned_at DESC);

-- The pinned section reads an organisation's assignments newest first.
CREATE INDEX ix_pin_assignments_tenant ON document_pin_assignments (tenant_id, pinned_at DESC);

CREATE TABLE document_pin_audience_members (
    assignment_id  UUID NOT NULL REFERENCES document_pin_assignments (assignment_id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    PRIMARY KEY (assignment_id, user_id)
);

-- "Is this user named by that assignment?" is asked per user when the pinned section is read.
CREATE INDEX ix_pin_audience_members_user ON document_pin_audience_members (user_id);

-- Carry every existing personal pin over as a pin visible to its owner only (USERS, one member),
-- so nobody loses a pin and nobody sees more than before.
INSERT INTO document_pin_assignments (tenant_id, document_id, pinned_by, audience_mode, pinned_at, updated_at)
SELECT tenant_id, document_id, user_id, 'USERS', pinned_at, pinned_at
FROM document_pins;

INSERT INTO document_pin_audience_members (assignment_id, user_id)
SELECT a.assignment_id, a.pinned_by
FROM document_pin_assignments a;

-- document_pins is left in place, unread, for one release so this can be rolled back; a later
-- migration drops it.
COMMENT ON TABLE document_pins IS
    'Superseded by document_pin_assignments (V110); no longer read or written. Drop in a later migration.';
