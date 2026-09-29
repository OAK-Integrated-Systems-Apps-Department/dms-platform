-- Transactional outbox for the RabbitMQ event backbone (Tech Stack Review).
-- Polymorphic by design: aggregate_type/aggregate_id have no FK, since this
-- table is shared across every current and future module.

CREATE TABLE outbox_events (
    outbox_event_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    aggregate_type      VARCHAR(100) NOT NULL,
    aggregate_id        UUID NOT NULL,
    event_type          VARCHAR(150) NOT NULL,
    payload             JSONB NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
    attempts            INT NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('pending', 'published', 'failed'))
);

-- Serves both the relay's polling query (status + createdAt ordering) and
-- typical per-aggregate lookups ("what events have we emitted for user X").
CREATE INDEX ix_outbox_events_status_created_at ON outbox_events (status, created_at);
CREATE INDEX ix_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);
CREATE INDEX ix_outbox_events_tenant_id ON outbox_events (tenant_id);
