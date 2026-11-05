-- 4.4 Bookmark Documents (BD): a private shortcut, never shared.
--
-- A bookmark is personal (document_bookmarks: one row per user and document) and never grants
-- anything - every read goes through DocumentService#visibleItems, the same access rules as the
-- document browser, so a bookmark can never widen what anyone can open (BD-NFR-004).
--
-- Unlike a pin (document_pin_assignments), a bookmark the caller placed on a document they can no
-- longer see (access revoked) or that is in the recycle bin is HIDDEN, not deleted, and reappears
-- if access or the document comes back - see BookmarkService. Only a hard purge removes the row
-- (BookmarkPurgeHandler, called from RecycleBinService; ON DELETE CASCADE does not fire on a purge,
-- since the document and version rows stay as tombstones).
--
-- Documents deleted for good (hard-deleted, not merely purged - see above) take their bookmarks
-- with them (ON DELETE CASCADE) as a backstop; in practice BookmarkPurgeHandler removes the row
-- before that could ever matter.

CREATE TABLE document_bookmarks (
    tenant_id      UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL REFERENCES users (user_id)     ON DELETE CASCADE,
    document_id    UUID        NOT NULL,
    note           VARCHAR(500),
    bookmarked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_id),
    CONSTRAINT fk_document_bookmarks_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE
);

-- The caller's own bookmarks, newest first (BD-NFR-002: list p95 under 1s for 500 bookmarks).
CREATE INDEX ix_document_bookmarks_user ON document_bookmarks (tenant_id, user_id, bookmarked_at DESC);

-- Removing a document's bookmarks when it is purged (BookmarkPurgeHandler).
CREATE INDEX ix_document_bookmarks_document ON document_bookmarks (tenant_id, document_id);

-- Nobody loses what they could already do: every role that holds docm.documents.view - the same
-- permission that gates seeing a document at all - gets the bookmark permission too, so "any user
-- who can view a document can bookmark it" holds from day one. NOT EXISTS rather than ON CONFLICT:
-- resource_scope may be NULL, which a unique constraint never treats as equal (see V15, V87).
WITH desired_grants AS (
    SELECT rp.tenant_id, rp.role_id, p_new.permission_id, rp.resource_scope
    FROM role_permissions rp
    JOIN permissions p_old ON p_old.permission_id = rp.permission_id
        AND p_old.permission_code = 'docm.documents.view'
    CROSS JOIN permissions p_new
    WHERE p_new.permission_code = 'bd.bookmark-unbookmark-documents.bookmark'
      AND p_new.tenant_id IS NULL
)
INSERT INTO role_permissions (tenant_id, role_id, permission_id, resource_scope)
SELECT dg.tenant_id, dg.role_id, dg.permission_id, dg.resource_scope
FROM desired_grants dg
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role_id = dg.role_id
      AND existing.permission_id = dg.permission_id
      AND existing.resource_scope IS NOT DISTINCT FROM dg.resource_scope
);
