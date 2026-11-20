-- DOC-14 read-only diagnostic of the folder tree. Run by an administrator against a COPY or a read replica, or directly:
-- it only SELECTs, and returns identifiers only (no folder names, no document metadata).

-- 1. Folders that are part of a parent cycle (walking up from the folder returns to it).
with recursive walk as (
    select folder_id as start_id, parent_folder_id as cur, 1 as steps
      from folders where not is_deleted
    union all
    select w.start_id, f.parent_folder_id, w.steps + 1
      from walk w join folders f on f.folder_id = w.cur and not f.is_deleted
     where w.cur is not null and w.cur <> w.start_id and w.steps < 100
)
select 'cycle' as problem, f.tenant_id, w.start_id as folder_id
  from walk w join folders f on f.folder_id = w.start_id
 where w.cur = w.start_id
 order by f.tenant_id, w.start_id;

-- 2. Live folders deeper than the 20-level rule (a 21st ancestor exists).
with recursive walk as (
    select folder_id as start_id, parent_folder_id as cur, 1 as steps
      from folders where not is_deleted
    union all
    select w.start_id, f.parent_folder_id, w.steps + 1
      from walk w join folders f on f.folder_id = w.cur and not f.is_deleted
     where w.cur is not null and w.steps < 25
)
select 'too deep' as problem, f.tenant_id, w.start_id as folder_id
  from walk w join folders f on f.folder_id = w.start_id
 where w.steps >= 21 and w.cur is not null
 group by f.tenant_id, w.start_id
 order by f.tenant_id, w.start_id;

-- 3. Live folders whose parent is deleted.
select 'live under deleted parent' as problem, c.tenant_id, c.folder_id, c.parent_folder_id
  from folders c join folders p on p.folder_id = c.parent_folder_id
 where not c.is_deleted and p.is_deleted
 order by c.tenant_id, c.folder_id;

-- 4. Parents in another tenant (the composite foreign key should make this impossible).
select 'parent in another tenant' as problem, c.tenant_id, c.folder_id, c.parent_folder_id
  from folders c join folders p on p.folder_id = c.parent_folder_id
 where c.tenant_id <> p.tenant_id;

-- 5. Missing parents (the foreign key should make this impossible).
select 'missing parent' as problem, c.tenant_id, c.folder_id, c.parent_folder_id
  from folders c
 where c.parent_folder_id is not null
   and not exists (select 1 from folders p where p.folder_id = c.parent_folder_id);

-- 6. Related, for the separate document-side follow-up: live documents filed in a deleted folder.
select 'document in deleted folder' as problem, d.tenant_id, d.document_id, d.folder_id
  from documents d join folders f on f.folder_id = d.folder_id
 where f.is_deleted and not d.is_deleted
 order by d.tenant_id, d.document_id;
