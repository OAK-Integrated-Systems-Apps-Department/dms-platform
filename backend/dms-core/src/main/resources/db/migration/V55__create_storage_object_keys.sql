-- Document encryption at rest (Module DE, DE-FR-001..006).
--
-- Every object the application writes to storage (documents, staged and
-- quarantined uploads, import files, export ZIPs) is encrypted by the
-- application before it leaves the process: streaming AES-256-GCM with its
-- own random data key. The data key itself is never stored - only its
-- WRAPPED form, encrypted by the key service (OpenBao Transit in production)
-- whose master key never leaves that service. Storage alone therefore never
-- holds readable content, and the keys live separately from the data
-- (DE-FR-004/006, DE-NFR-001).
--
-- One row per encrypted object. An object without a row was written before
-- encryption was turned on (or deliberately unencrypted - a staging object
-- a cloud scanner must read in place) and is served as stored; the
-- background "encrypt existing documents" job converts documents to
-- encrypted objects. Rotating the master key re-wraps these rows - the
-- objects themselves are never rewritten (DE-FR-005).

CREATE TABLE storage_object_keys (
    storage_object_key_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_location_id   UUID          NOT NULL REFERENCES storage_locations (storage_location_id),
    object_key            VARCHAR(1000) NOT NULL,
    -- The data key, wrapped by the key service, e.g. "vault:v3:..." for OpenBao Transit.
    wrapped_key           TEXT          NOT NULL,
    key_provider          VARCHAR(30)   NOT NULL,
    -- The master-key version that wrapped it; lower than the current one = due for re-wrapping.
    key_version           INT           NOT NULL,
    algorithm             VARCHAR(40)   NOT NULL,
    -- Plaintext size - the stored object is slightly bigger (header + a tag per segment).
    plaintext_size        BIGINT        NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    rewrapped_at          TIMESTAMPTZ,
    CONSTRAINT uq_storage_object_keys_object UNIQUE (storage_location_id, object_key),
    CONSTRAINT ck_storage_object_keys_provider CHECK (key_provider IN ('openbao', 'local-dev')),
    CONSTRAINT ck_storage_object_keys_version CHECK (key_version > 0),
    CONSTRAINT ck_storage_object_keys_size CHECK (plaintext_size >= 0)
);

-- "How many objects still use an old key version?" - the key management screen and the re-wrap job.
CREATE INDEX ix_storage_object_keys_version ON storage_object_keys (key_provider, key_version);
