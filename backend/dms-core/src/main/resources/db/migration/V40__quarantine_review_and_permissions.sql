-- Virus & malware scanning, Phase 2: quarantine review (VMSBDI FR-005 /
-- alternate flow 3), permissions (NFR-005). See docs/scanning-architecture.md.
--
-- Releasing a false positive works in two ways:
--
--   1. Re-scan (docm.quarantine.release) - the quarantined file is copied back
--      into staging and scanned again on the tenant's current engine through
--      the ordinary scan gate. A clean verdict promotes it the normal way, so
--      the V39 gate trigger is satisfied honestly and needs nothing new.
--
--   2. Override (docm.quarantine.override) - only after a re-scan was refused
--      (the engine still flags the file). A deliberate, reason-mandatory,
--      HIGH-audited hole in the control, recorded as a scan_results row with
--      the new status 'released_override' and a version with the new
--      scan_status 'overridden'. The gate trigger is widened to accept that
--      pair ONLY when it is tied to a quarantine record - i.e. to bytes the
--      scanner actually scanned and quarantined - that has been reviewed and
--      released by the same user, for this document, with the same hash. An
--      ordinary unscanned insert is still rejected exactly as before.
--
-- Both kinds of release attempt are scan_results rows pointing back at the
-- original quarantined scan result (released_from_scan_result_id).

-- =====================================================================
-- 1. scan_results: release attempts
-- =====================================================================
ALTER TABLE scan_results
    ADD COLUMN released_from_scan_result_id UUID,
    ADD COLUMN review_requested_by UUID REFERENCES users (user_id),
    ADD COLUMN review_reason VARCHAR(1000),
    ADD CONSTRAINT fk_scan_results_released_from FOREIGN KEY (tenant_id, released_from_scan_result_id)
        REFERENCES scan_results (tenant_id, scan_result_id);

ALTER TABLE scan_results DROP CONSTRAINT ck_scan_results_status;
ALTER TABLE scan_results
    ADD CONSTRAINT ck_scan_results_status CHECK (status IN
        ('pending', 'clean', 'infected', 'failed', 'unscannable', 'quarantined', 'released_override'));

ALTER TABLE scan_results DROP CONSTRAINT ck_scan_results_version_only_when_clean;
ALTER TABLE scan_results
    ADD CONSTRAINT ck_scan_results_version_only_when_clean
        CHECK (document_version_id IS NULL OR status IN ('clean', 'released_override'));

-- Every release attempt names who asked for it and why.
ALTER TABLE scan_results
    ADD CONSTRAINT ck_scan_results_release_attempt CHECK (released_from_scan_result_id IS NULL
        OR (review_requested_by IS NOT NULL AND btrim(coalesce(review_reason, '')) <> ''));

-- An override is only ever a release of an earlier quarantined result, and
-- only exists once it has produced its version.
ALTER TABLE scan_results
    ADD CONSTRAINT ck_scan_results_override_reviewed CHECK (status <> 'released_override'
        OR (released_from_scan_result_id IS NOT NULL AND document_version_id IS NOT NULL));

-- At most one in-flight or successful release per quarantined file. Refused
-- ('quarantined'), 'failed' and 'unscannable' attempts don't count, so a
-- refused re-scan can be retried or followed by an override.
CREATE UNIQUE INDEX uq_scan_results_one_active_release
    ON scan_results (released_from_scan_result_id)
    WHERE released_from_scan_result_id IS NOT NULL
      AND status IN ('pending', 'infected', 'clean', 'released_override');

CREATE INDEX ix_scan_results_released_from ON scan_results (released_from_scan_result_id);

-- =====================================================================
-- 2. document_versions: 'overridden' + the widened gate
-- =====================================================================
ALTER TABLE document_versions DROP CONSTRAINT ck_document_versions_scan_status;
ALTER TABLE document_versions
    ADD CONSTRAINT ck_document_versions_scan_status CHECK (scan_status IN
        ('pending', 'clean', 'infected', 'failed', 'quarantined', 'overridden'));

-- Same function V39 created (the constraint trigger keeps pointing at it).
-- Only the scan-result check changes: a clean version needs a clean scan
-- result, exactly as before; an overridden version needs a released_override
-- result that is tied - through a released quarantine record reviewed by the
-- same user, for this document, with the same hash - to an original result
-- the scanner quarantined.
CREATE OR REPLACE FUNCTION document_versions_require_clean_scan() RETURNS trigger AS
$$
BEGIN
    IF NEW.scan_status NOT IN ('clean', 'overridden') THEN
        RAISE EXCEPTION 'document_versions %: a new version must be created with scan_status = clean (or overridden by an administrator release), got %',
            NEW.version_id, NEW.scan_status;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM storage_locations sl
                   WHERE sl.storage_location_id = NEW.storage_location_id
                     AND sl.purpose = 'document') THEN
        RAISE EXCEPTION 'document_versions %: storage location % is not a document location',
            NEW.version_id, NEW.storage_location_id;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM scan_results sr
                   WHERE sr.tenant_id = NEW.tenant_id
                     AND sr.document_version_id = NEW.version_id
                     AND sr.file_hash = NEW.file_hash
                     AND sr.is_deleted = false
                     AND (
                          (sr.status = 'clean' AND NEW.scan_status = 'clean')
                       OR (sr.status = 'released_override' AND NEW.scan_status = 'overridden'
                           AND sr.review_requested_by IS NOT NULL
                           AND btrim(coalesce(sr.review_reason, '')) <> ''
                           AND EXISTS (SELECT 1
                                       FROM quarantine_records q
                                       JOIN scan_results orig ON orig.scan_result_id = q.scan_result_id
                                       WHERE q.scan_result_id = sr.released_from_scan_result_id
                                         AND q.tenant_id = NEW.tenant_id
                                         AND q.status = 'released'
                                         AND q.reviewed_by = sr.review_requested_by
                                         AND q.released_document_id = NEW.document_id
                                         AND q.file_hash = NEW.file_hash
                                         AND orig.status = 'quarantined')))) THEN
        RAISE EXCEPTION 'document_versions %: no clean scan result (or reviewed quarantine override) with a matching file hash is linked to this version - every version must pass through the scan gate',
            NEW.version_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================
-- 3. quarantine_records: list by detection time
-- =====================================================================
CREATE INDEX ix_quarantine_records_tenant_created ON quarantine_records (tenant_id, created_at DESC);

-- =====================================================================
-- 4. Permissions (module DOCM, seeded like V33). Granted to no role - an
--    administrator assigns them. TENANT_ADMIN/PLATFORM_ADMIN hold them
--    implicitly, as they do every permission (AuthorizationService).
-- =====================================================================
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('RELEASE', 'Release'),
    ('OVERRIDE_RELEASE', 'Override release')
) AS v(action_code, action_name)
JOIN permission_modules pm ON pm.module_code = 'DOCM'
ON CONFLICT (module_id, action_code) DO NOTHING;

DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT v.permission_code, v.name, pa.action_id
        FROM (VALUES
            ('docm.quarantine.view', 'View quarantined files', 'VIEW'),
            ('docm.quarantine.release', 'Release a quarantined file after re-scan', 'RELEASE'),
            ('docm.quarantine.delete', 'Permanently delete a quarantined file', 'DELETE'),
            ('docm.quarantine.override', 'Force-release a file the scanner still flags', 'OVERRIDE_RELEASE')
        ) AS v(permission_code, name, action_code)
        JOIN permission_actions pa ON pa.action_code = v.action_code
        JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'DOCM'
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V40: % docm.quarantine.* permission(s) seeded (granted to no role)', n;
END;
$$;
