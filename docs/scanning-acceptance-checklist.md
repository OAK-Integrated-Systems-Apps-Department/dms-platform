# Malware Scanning - Manual Acceptance Checklist (Phases 1 + 2)

There is no automated test harness for this feature; this is the manual
acceptance run. It is written against **ClamAV** on the local stack; what
differs on **Azure Defender** and **AWS GuardDuty** is listed at the end.
Architecture: [scanning-architecture.md](scanning-architecture.md).

## Setup

1. `docker compose up -d clamav mailpit` (plus the rest of the stack) and
   wait for `oakdms-clamav` to be healthy (the first start downloads
   signatures - several minutes).
2. Mail lands in Mailpit: <http://localhost:8026>.
3. Accounts, all in tenant **T1** unless noted:
   - **A** - a tenant admin (`TENANT_ADMIN`).
   - **R** - a user whose role grants only `docm.quarantine.view` and
     `docm.quarantine.release`.
   - **U** - a user whose role grants only `docm.documents.create`.
   - **T2-A** - the admin of a second tenant **T2**.
4. The EICAR test file (a harmless, industry-standard test string every
   engine detects), saved as `eicar.txt`:
   `X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*`

Record pass/fail and any notes against each step.

## Scan gate

| # | Step | Expected |
|---|---|---|
| 1 | U uploads `eicar.txt` (`POST /api/v1/documents`). | `422`, ticket `status: quarantined`, threat `Eicar-Test-Signature`. No new row in `documents`. A `quarantine_records` row (`held`). The object exists under the quarantine storage path. Audit: `DOCUMENT_SCAN_INFECTED`, `DOCUMENT_QUARANTINED`. |
| 2 | Check Mailpit after step 1. | Within seconds: one mail to U ("Your upload was blocked…"), one each to A and R ("[Quarantine] Threat detected…") with a link to `/admin/quarantine/{id}`. No attachment, no link to the file. |
| 3 | Stop Mailpit (`docker stop oakdms-mailpit`), repeat step 1, start it again. | Upload still `422 quarantined`, record created. dms-core logs a WARN for the failed mail; nothing else is affected. |
| 4 | U uploads a clean PDF. | `201`, `Location: /api/v1/documents/{id}`, a document row, `document_versions.scan_status = clean`. No quarantine row, no mail. |
| 5 | U uploads a password-protected `.docx` and a PDF with a user password. | Each `422`, `status: unscannable`, message about removing the protection. No quarantine row, no mail. |
| 6 | `docker stop oakdms-clamav`; U uploads a clean file. | `202`, `status: pending`, `retrying: true`, no document. |
| 7 | `docker start oakdms-clamav`; wait until healthy, then ~1 min. | `GET /api/v1/ingestion-tickets/{id}` shows `clean` with a documentId; the document downloads. Nothing was promoted while the engine was down. |

## Quarantine review

| # | Step | Expected |
|---|---|---|
| 8 | A opens **Quarantine** in the sidebar (Governance). | The list shows the EICAR files: filename, threat, size, detection time, uploader, status `Held`. Search by filename/threat, status filter and date range all narrow the list. |
| 9 | A opens a record. | Detail shows the record, the scan result (engine `ClamAV`, engine + signature version, scan time, duration, uploader, SHA-256) and an empty release history. **No download, preview or open action anywhere.** |
| 10 | A clicks **Release…**, confirm is disabled until a reason is typed; confirm. | Toast "The re-scan still detects …". Record stays `Held`; history shows "Refused (still detected)" with A, the reason and the signature version. Audit `QUARANTINE_RELEASE_REFUSED` (HIGH, with reason). No new quarantine record, no mail. |
| 11 | On that record, A clicks **Override…** (now visible), gives a reason, confirms. | Toast "force-released…". Record `Released`, reviewed by A with the reason and the released document id. A document exists owned by U with `scan_status = overridden`, and it downloads with the original bytes. The quarantine object is gone from storage. Audit `QUARANTINE_OVERRIDE_RELEASED` (HIGH). |
| 12 | On a different, never-released `Held` record, call `POST /api/v1/quarantine/{id}/override-release` directly. | `409 QUARANTINE_OVERRIDE_REQUIRES_RESCAN`. The Override button is not shown on that record. |
| 13 | **Release after a signature correction.** Add a custom signature: `docker exec oakdms-clamav sh -c "echo 'OakFalsePositive.Test:0:*:<hex of a marker string>' > /var/lib/clamav/oak-fp.ndb"`, then reload (`echo RELOAD \| nc localhost 3310`). Upload a benign text file containing the marker; it is quarantined as `OakFalsePositive.Test.UNOFFICIAL`. Remove the `.ndb`, reload again, then A releases it. | Toast "The re-scan came back clean…". Record `Released`; the document has `scan_status = clean` and is owned by U; the quarantine object is gone. Audit `QUARANTINE_RELEASED`. |
| 14 | A clicks **Delete…** on a `Held` record, gives a reason, confirms. | Record `Deleted` with reviewer/time/reason; the object is gone from quarantine storage; the record is still listed (status filter "Deleted"). Audit `QUARANTINE_DELETED`. Calling delete again → `409`. |
| 15 | In psql, insert a `document_versions` row with `scan_status = 'overridden'` (or `'clean'`) that has no matching scan/quarantine chain, and commit. | The commit fails: "no clean scan result (or reviewed quarantine override) … every version must pass through the scan gate". |

## Permissions and isolation

| # | Step | Expected |
|---|---|---|
| 16 | Log in as U. | No **Quarantine** item in the sidebar. `GET /api/v1/quarantine`, `GET /{id}` and each POST action return `403`. Visiting `/admin/quarantine` directly shows the access-denied state. |
| 17 | Log in as R. | Sees the list and the detail, and **Release…**, but not **Override…** or **Delete…**. Calling `override-release` or `delete` directly returns `403`. |
| 18 | Log in as T2-A. | T2's quarantine list does not include any T1 record. `GET`/`release`/`delete` on a T1 record id return `404`. |
| 19 | Check the new permissions in **Roles → edit role**. | `docm.quarantine.view/release/delete/override` appear under Document Management and are assigned to no role until an admin grants them. |

## Azure Defender / AWS GuardDuty differences

Run the same steps against a sandbox account with the provider enabled
(Defender on-upload scanning with blob-index-tag results; GuardDuty Malware
Protection with "tag scanned objects"), with the staging location on that
provider's storage.

- **Asynchronous verdicts.** Uploads (steps 1, 4) and re-scan releases
  (steps 10, 13) return `202 pending`. The verdict arrives through the
  callback (if its secret is configured) or the scheduler polling the blob
  or object tag. The detail screen shows "A re-scan release is in progress"
  and refreshes itself.
- **Threat names** appear only when a callback delivered them. Tag polling
  alone records `UNKNOWN (see provider finding)`.
- **Engine and signature versions** are not exposed by either provider and
  show as "not exposed by this provider".
- **Step 13** cannot be simulated with a custom signature. In the cloud a
  clean re-scan depends on the provider's definitions changing; use the
  override path (steps 10–11) to exercise release.
- **Step 6/7 (engine unavailable)**: revoke the adapter's tag-read permission
  (or break its credentials) instead of stopping a container; restore it and
  confirm the scheduler completes the upload.
- **Step 5**: GuardDuty reports password-protected files as `UNSUPPORTED`,
  which maps to `unscannable`; the gate's own encryption check also catches
  them before upload.
