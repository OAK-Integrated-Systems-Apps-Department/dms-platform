-- 3.1 Workflow Automation, Phase 2 - threaded comments on documents, with
-- @mentions.
--
--   document_comments  belongs to a document and optionally to a workflow instance; two-level threads
--   comment_mentions   one row per user mentioned in a comment
--
-- See docs/workflow-phase2-plan.md (§2, §9). Points worth knowing:
--   * Reading and writing need docm.documents.view (decision 9). A mentioned
--     user without it still reads that comment; had_document_access records
--     why they could not open the document (decision 10).
--   * comment_mentions keeps only created_at, as specified (N2): rows are
--     inserted once; notified_at is set after the mail is sent.
--   * The instance must belong to the comment's document - checked in the
--     service (a composite FK can't express it).

CREATE TABLE document_comments (
    comment_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id          UUID NOT NULL,
    workflow_instance_id UUID,
    parent_comment_id    UUID,
    body                 VARCHAR(4000) NOT NULL,
    author_user_id       UUID NOT NULL REFERENCES users (user_id),
    edited_at            TIMESTAMPTZ,
    created_by           UUID REFERENCES users (user_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID REFERENCES users (user_id),
    updated_at           TIMESTAMPTZ,
    is_deleted           BOOLEAN NOT NULL DEFAULT false,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_document_comments_tenant_comment UNIQUE (tenant_id, comment_id),
    CONSTRAINT fk_document_comments_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_document_comments_instance FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_document_comments_parent FOREIGN KEY (tenant_id, parent_comment_id)
        REFERENCES document_comments (tenant_id, comment_id),
    CONSTRAINT ck_document_comments_body CHECK (length(btrim(body)) > 0),
    CONSTRAINT ck_document_comments_not_own_parent CHECK (parent_comment_id IS NULL OR parent_comment_id <> comment_id)
);

CREATE INDEX ix_document_comments_tenant_id ON document_comments (tenant_id);
CREATE INDEX ix_document_comments_document ON document_comments (tenant_id, document_id, created_at)
    WHERE is_deleted = false;
CREATE INDEX ix_document_comments_instance ON document_comments (workflow_instance_id, created_at)
    WHERE workflow_instance_id IS NOT NULL AND is_deleted = false;
CREATE INDEX ix_document_comments_parent ON document_comments (parent_comment_id)
    WHERE parent_comment_id IS NOT NULL;

CREATE TABLE comment_mentions (
    mention_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    comment_id          UUID NOT NULL,
    mentioned_user_id   UUID NOT NULL REFERENCES users (user_id),
    had_document_access BOOLEAN NOT NULL,
    notified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_comment_mentions_tenant_mention UNIQUE (tenant_id, mention_id),
    CONSTRAINT fk_comment_mentions_comment FOREIGN KEY (tenant_id, comment_id)
        REFERENCES document_comments (tenant_id, comment_id),
    CONSTRAINT uq_comment_mentions_comment_user UNIQUE (comment_id, mentioned_user_id)
);

CREATE INDEX ix_comment_mentions_tenant_id ON comment_mentions (tenant_id);
CREATE INDEX ix_comment_mentions_user ON comment_mentions (tenant_id, mentioned_user_id, created_at DESC);
