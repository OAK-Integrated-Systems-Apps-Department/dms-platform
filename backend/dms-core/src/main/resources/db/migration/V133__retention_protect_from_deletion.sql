-- 6.1 follow-up: stop premature deletion (CRM business objective: "neither premature deletion nor over-retention").
--
-- protect_from_deletion  when ON, a document that a retention rule still covers cannot be deleted by anyone until its total
--                        retention (active + archive period) has ended - or it has been flagged for review, which is the point
--                        an administrator decides about disposal. A document no rule covers is unaffected. OFF by default, so
--                        nothing changes for an organisation until its administrator turns it on.

ALTER TABLE tenant_retention_settings
    ADD COLUMN protect_from_deletion BOOLEAN NOT NULL DEFAULT false;
