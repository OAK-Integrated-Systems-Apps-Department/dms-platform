-- impersonated_by has always been a bare UUID with no human-readable
-- snapshot alongside it, unlike actor_id (which has actor_display_name).
-- The audit log's own detail view can resolve actor_display_name for
-- "who did this" but had no equivalent for "who were they impersonating
-- as" - showing a raw UUID instead. Adds the same kind of point-in-time
-- snapshot actor_display_name already provides, so the record remains
-- self-contained (readable without a live join back to a users row that
-- may since have been renamed or deleted) - see actor_display_name's own
-- reasoning in V23.
ALTER TABLE audit_events ADD COLUMN impersonated_by_display_name VARCHAR(255);
