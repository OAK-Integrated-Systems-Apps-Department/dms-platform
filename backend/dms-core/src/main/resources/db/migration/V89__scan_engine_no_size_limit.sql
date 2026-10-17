-- No per-file upload limit any more: ClamAV scans a file larger than the 2147483645
-- bytes clamd reads of one stream in overlapping segments (ClamAvScanAdapter), so the
-- 2047 MB cap V68 set is lifted. 9223372036854775807 (Long.MAX_VALUE) means "no limit"
-- and keeps ck_scan_engines_max_file_size (> 0) satisfied.
-- Rows still on the old platform default (2047 MB, or 100 MB from before V68) are
-- raised; an engine an administrator set to some other size is left as it is.
update scan_engines
   set max_file_size_bytes = 9223372036854775807
 where max_file_size_bytes in (2146435072, 104857600);
