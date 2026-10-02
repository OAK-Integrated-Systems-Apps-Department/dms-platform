-- Crypto-shredding (V59) keeps each key row as a tombstone instead of
-- deleting it: the wrapped data key is overwritten and shredded_at is set.
--
-- A deleted row would make the object look like one stored before
-- encryption, and its ciphertext would be served as if it were the file. A
-- tombstone makes a read fail plainly ("its key was destroyed"), keeps
-- pointing at where the ciphertext lives, and is skipped by the startup key
-- check, re-wrapping and the key screen's counts.

ALTER TABLE storage_object_keys ADD COLUMN shredded_at TIMESTAMPTZ;
