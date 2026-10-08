-- Why a bulk import row's file was refused at intake (virus found, or it
-- couldn't be scanned). The refused file is never stored, and the upload's
-- own transaction rolls back - without this, the row later read only "No file
-- was uploaded for this row", hiding that a virus was the reason. Written in
-- the scan's own transaction (IntakeVirusScanner), cleared when a clean file
-- for the row arrives.
ALTER TABLE import_items ADD COLUMN intake_blocked_reason VARCHAR(500);
