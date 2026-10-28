-- Uploads go up to 2047 MB - the most ClamAV scans of one file (clamd caps it at
-- 2147483645 bytes) - instead of 100 MB.
-- ScanEngineBootstrap only seeds an engine that doesn't exist yet, so rows already
-- seeded with the old defaults are raised here. An engine an administrator set
-- to some other size or timeout is left as it is.
update scan_engines
   set max_file_size_bytes = 2146435072
 where max_file_size_bytes = 104857600;

update scan_engines
   set timeout_seconds = 900
 where timeout_seconds = 60;
