# 1.5 Sharing and External Access (SEA) - design and API contract

Internal sharing (a user or role at view / edit / manage on a folder or document) already exists
(`folder_access`, `document_access`, the "Manage access" sheet). This module adds the **external** half and a
single Google-Drive-style **Share** entry point for files AND folders.

## Concepts

| Thing | What it is |
|---|---|
| Share link | Non-guessable token (32 random bytes, only its SHA-256 is stored) to ONE document or folder. `view` or `download`. Optional expiry (date) and/or max views, optional password (BCrypt hash), optional recipient email (we mail the link). Revocable. |
| Guest account | Tenant-scoped external person (email + password they set from an invite mail). Signs in at `/portal`. |
| Guest grant | A guest's access to a document or folder (subtree) at `view`/`download`, optional expiry, revocable. |
| Confidential flag | `documents.confidential` / `folders.confidential`. Confidential items (or anything inside a confidential folder), documents under legal hold, and documents not in the `active` lifecycle state cannot be shared externally; a tenant admin may override a confidential block with a reason (audited). |
| Tenant policy | `tenant_sharing_settings`: external sharing on/off, default + max expiry days, password attempt limit + lockout minutes, guest inactivity days, what to do with live links when something is marked confidential (`flag` for admin review or `revoke`). |

Denied states are **pages, not errors** (SEA-NFR-003): the public info endpoint always answers 200 with a `status`.

## Authenticated API (`/api/v1/sharing`, cookie session like the rest)

Permissions: create link `SEA_CREATE_LINK`; password/expiry `SEA_SET_EXPIRY_PASSWORD` (optional fields ignored/rejected without it);
revoke `SEA_REVOKE_SINGLE`; guests `SEA_INVITE_GUEST` / `SEA_UPDATE_GUEST` / `SEA_REVOKE_GUEST` / `SEA_VIEW_GUESTS`; log `SEA_VIEW_ACTIVITY`.
Tenant admins hold all. The caller must also have **manage** (documents: edit) access on the target.
Settings read: any authenticated; settings write, "all links", review queue, override: tenant admin only.

```
GET  /settings                         -> Settings
PUT  /settings        Settings         -> Settings                         (tenant admin)
GET  /eligibility?targetType=document|folder&targetId=   -> { canShare, canOverride, reasons: string[], confidential: boolean /*the item's OWN mark*/ }
GET  /links?targetType=&targetId=      -> Link[]     (links on one item, newest first)
GET  /links/all?status=active|expired|revoked|review&page=0&size=25  -> { items: Link[], total, page, size }   (tenant admin; every link in the org)
POST /links  CreateLink                -> { link: Link, url: string, emailSent: boolean }   (url only ever returned here)
DELETE /links/{linkId}                 -> 204   (owner or tenant admin; immediate)
POST /links/{linkId}/clear-review      -> 204   (tenant admin: keep a flagged link)
GET  /guests                           -> Guest[]
POST /guests/invite  { email, displayName? } -> Guest   (creates + mails invite; idempotent per email)
PATCH /guests/{guestId} { status: "active"|"disabled" } -> Guest
DELETE /guests/{guestId}               -> 204   (removes account and all grants)
GET  /guest-grants?targetType=&targetId= -> GuestGrant[]
POST /guest-grants  CreateGrant        -> GuestGrant    (creates the guest + invite mail when the email is new)
PATCH /guest-grants/{grantId} { access?, expiresAt? } -> GuestGrant
DELETE /guest-grants/{grantId}         -> 204
PUT  /confidential { targetType, targetId, confidential: boolean, reason? } -> { flaggedLinks: number, revokedLinks: number }
GET  /events?targetType=&targetId=&linkId=&page=&size=  -> { items: ShareEvent[], total, page, size }  (SEA_VIEW_ACTIVITY or target manager)
```

```ts
Settings    = { externalSharingEnabled: boolean, defaultExpiryDays: number, maxExpiryDays: number,
                maxPasswordAttempts: number, lockoutMinutes: number, guestInactivityDays: number,
                onReclassify: "flag" | "revoke" }
CreateLink  = { targetType: "document"|"folder", targetId: string, access: "view"|"download",
                expiresAt?: string /*ISO; omitted => default expiry; null => never only if policy max allows (it never does: maxExpiryDays caps it)*/,
                maxViews?: number, password?: string, recipientEmail?: string, message?: string,
                overrideRestriction?: boolean, overrideReason?: string }
Link        = { linkId, targetType, targetId, targetName, access, status: "active"|"expired"|"revoked"|"exhausted"|"locked",
                passwordProtected: boolean, expiresAt: string|null, maxViews: number|null, viewCount: number,
                recipientEmail: string|null, createdBy: string, createdByName: string, createdAt: string,
                lastAccessedAt: string|null, needsReview: boolean, overridden: boolean, revokedAt: string|null }
Guest       = { guestId, email, displayName, status: "invited"|"active"|"disabled", disabledReason: string|null,
                lastLoginAt: string|null, createdAt: string, grantCount: number }
CreateGrant = { email, displayName?, targetType, targetId, access: "view"|"download", expiresAt?: string,
                overrideRestriction?: boolean, overrideReason?: string }
GuestGrant  = { grantId, guestId, email, displayName, guestStatus, targetType, targetId, targetName, access,
                expiresAt: string|null, revokedAt: string|null, createdAt: string }
ShareEvent  = { eventId, at: string, type: "link_created"|"link_opened"|"link_viewed"|"link_downloaded"|"link_revoked"|"link_expired"|"password_failed"|"link_locked"|"link_flagged"|"link_review_cleared"|"guest_invited"|"guest_grant_created"|"guest_grant_revoked"|"guest_signed_in"|"guest_viewed"|"guest_downloaded"|"guest_disabled",
                linkId: string|null, guestId: string|null, actor: string /*who: user name, guest email, or "Anonymous"*/,
                ip: string|null, targetName: string|null, detail: string|null }
```

Errors on the authenticated API use the project's usual `{code, message}` problem body; share-specific codes:
`SHARING_DISABLED`, `SHARING_NOT_ALLOWED` (eligibility reasons), `SHARING_EXPIRY_TOO_LONG`, `SHARING_OVERRIDE_REQUIRES_ADMIN`.

## Public API (no session) - `/api/v1/public/share` (links) and `/api/v1/public/portal` (guests)

```
GET  /api/v1/public/share/{token}            -> LinkInfo              (always 200)
POST /api/v1/public/share/{token}/open  { password? }  -> OpenResult (always 200; counts one view on success)
GET  /api/v1/public/share/{token}/items?access=<accessToken>&folderId=   -> Listing      (folder links)
GET  /api/v1/public/share/{token}/content?access=<accessToken>&documentId=&download=true|false -> bytes
```
```ts
LinkInfo   = { status: "active"|"expired"|"revoked"|"exhausted"|"locked"|"unavailable", passwordRequired: boolean,
               targetType?: "document"|"folder", name?: string, access?: "view"|"download", expiresAt?: string|null,
               organisation?: string, sharedBy?: string, lockedUntil?: string|null }
OpenResult = { ok: boolean, status: LinkInfo["status"]|"wrong_password", attemptsRemaining?: number,
               lockedUntil?: string, accessToken?: string, expiresInSeconds?: number }
Listing    = { folder: { id, name }, breadcrumb: { id, name }[] /*from the shared root down*/,
               items: { type: "document"|"folder", id, name, mimeType?: string, sizeBytes?: number, updatedAt?: string }[] }
```
`accessToken` is an HMAC-signed, 30-minute token bound to that link; it is passed as a query param (iframes/img/downloads
cannot send headers). `content` with `download=true` needs the link to be `download`, else 403. Non-PDF Office documents are served
as their PDF rendition when `download=false`. Every call re-checks the link (revoked/expired mid-session stops immediately).

```
POST /api/v1/public/portal/accept-invite { token, password }                  -> { ok }   (password min 10 chars)
POST /api/v1/public/portal/login  { email, password, tenantId? }               -> PortalLogin
GET  /api/v1/public/portal/me          header X-Guest-Token                    -> { email, displayName, organisation }
GET  /api/v1/public/portal/items?parentFolderId=   header X-Guest-Token        -> PortalListing
GET  /api/v1/public/portal/content?documentId=&download=&access=<guestToken>   -> bytes
GET  /api/v1/public/portal/invite/{token}                                      -> { email, organisation, valid }
```
```ts
PortalLogin   = { ok: boolean, status: "ok"|"invalid"|"disabled"|"locked"|"choose_organisation",
                  token?: string, expiresInSeconds?: number, organisations?: { tenantId, name }[] }
PortalListing = { breadcrumb: { id, name }[], items: { type, id, name, mimeType?, sizeBytes?, updatedAt?, access: "view"|"download" }[] }
```
`parentFolderId` omitted => the guest's granted roots. Guest tokens last 8 hours and are re-validated against the guest's
status and the grant on every call. Invite links: `{frontend}/portal/accept?token=...&org=<tenantId>`; share links:
`{frontend}/s/{token}`; portal sign-in `{frontend}/portal?org=<tenantId>`.

## Alternate flows implemented

* Wrong password N times (`maxPasswordAttempts`) -> link locked for `lockoutMinutes`; shown as `locked` with `lockedUntil`.
* Marking an item confidential -> live links on it and inside it are flagged `needsReview` (default) or auto-revoked, per `onReclassify`.
  A flagged link is **suspended** (the recipient sees "unavailable") until a tenant administrator keeps it (`clear-review`) or revokes it.
  Guest grants on it are always revoked - a grant is standing access with no review step to wait in.
  Only someone who manages the item can mark it; only a tenant administrator can clear the mark.
* Guests with no sign-in for `guestInactivityDays` are disabled by a nightly sweep (`disabledReason = "inactivity"`).

## Audit / reporting

Every creation, open, view, download, revocation, lock, review, guest invite/grant/sign-in is a row in `share_events`
(identity or IP, timestamp, action) and creation / revocation / settings / override / confidential changes also go to the
tenant audit trail (`SHARE_LINK_*`, `GUEST_*`, `SHARING_SETTINGS_CHANGED`, `DOCUMENT_CONFIDENTIAL_CHANGED`).

## Rules worth knowing

* External sharing is **off** for an organisation until a tenant administrator turns it on (precondition 1.5.4).
* Sharing needs *edit* access to the item plus the SEA permission code; "view and download" needs `set-permission-level`,
  and an expiry / view limit / password needs `set-expiry-password`.
* An administrator's confidential override covers only the item they shared. Inside a shared folder, confidential
  sub-folders and documents are always hidden, as are documents under legal hold or no longer `active` (retention) -
  nobody can override retention or legal hold.
* The link's item name is shown on the password screen (the sender told the recipient what it is anyway).
* "Wrong password" attempts are counted per link under a row lock, so they can't be raced past the limit.

## Deliberate limits

* Password hashes are BCrypt (one-way - stronger than "encrypted"); link tokens are stored only as SHA-256.
* A "view" is one successful `open` (a page load that got through the password), not one HTTP request.
* View-only sharing serves the rendition inline with `Cache-Control: no-store`; it cannot stop a screenshot.
* Guests get `view`/`download` only - never edit.
