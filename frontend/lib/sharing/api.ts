import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson, apiFetchVoid } from "@/lib/api-fetch"

/*
 * Client for 1.5 Sharing and External Access - see docs/external-sharing-design.md for the contract.
 * Authenticated calls go through apiFetch (session cookie, refresh-and-retry). The public and portal calls
 * use plain fetch: a visitor has no session, and a 401 there must never bounce them to /login.
 */

const BASE = `${API_BASE_URL}/api/v1/sharing`
const PUBLIC_SHARE = `${API_BASE_URL}/api/v1/public/share`
const PUBLIC_PORTAL = `${API_BASE_URL}/api/v1/public/portal`

export type ShareTargetType = "document" | "folder"
export type ShareAccess = "view" | "download"
export type LinkStatus =
  | "active"
  | "expired"
  | "revoked"
  | "exhausted"
  | "locked"

export interface SharingSettings {
  externalSharingEnabled: boolean
  defaultExpiryDays: number
  maxExpiryDays: number
  maxPasswordAttempts: number
  lockoutMinutes: number
  guestInactivityDays: number
  onReclassify: "flag" | "revoke"
}

export interface Eligibility {
  canShare: boolean
  canOverride: boolean
  reasons: string[]
  /** The item's own confidential mark (not whether a folder above it carries one). */
  confidential: boolean
}

export interface CreateLinkRequest {
  targetType: ShareTargetType
  targetId: string
  access: ShareAccess
  expiresAt?: string
  maxViews?: number
  password?: string
  recipientEmail?: string
  message?: string
  overrideRestriction?: boolean
  overrideReason?: string
}

export interface ShareLink {
  linkId: string
  targetType: ShareTargetType
  targetId: string
  targetName: string
  access: ShareAccess
  status: LinkStatus
  passwordProtected: boolean
  expiresAt: string | null
  maxViews: number | null
  viewCount: number
  recipientEmail: string | null
  createdBy: string
  createdByName: string
  createdAt: string
  lastAccessedAt: string | null
  needsReview: boolean
  overridden: boolean
  revokedAt: string | null
}

export interface LinkCreated {
  link: ShareLink
  url: string
  emailSent: boolean
}

export interface LinksPage {
  items: ShareLink[]
  total: number
  page: number
  size: number
}

export type LinkListFilter = "active" | "expired" | "revoked" | "review"

export interface Guest {
  guestId: string
  email: string
  displayName: string
  status: "invited" | "active" | "disabled"
  disabledReason: string | null
  lastLoginAt: string | null
  createdAt: string
  grantCount: number
}

export interface CreateGrantRequest {
  email: string
  displayName?: string
  targetType: ShareTargetType
  targetId: string
  access: ShareAccess
  expiresAt?: string
  overrideRestriction?: boolean
  overrideReason?: string
}

export interface GuestGrant {
  grantId: string
  guestId: string
  email: string
  displayName: string
  guestStatus: Guest["status"]
  targetType: ShareTargetType
  targetId: string
  targetName: string
  access: ShareAccess
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}

export type ShareEventType =
  | "link_created"
  | "link_opened"
  | "link_viewed"
  | "link_downloaded"
  | "link_revoked"
  | "link_expired"
  | "password_failed"
  | "link_locked"
  | "link_flagged"
  | "link_review_cleared"
  | "guest_invited"
  | "guest_grant_created"
  | "guest_grant_revoked"
  | "guest_signed_in"
  | "guest_viewed"
  | "guest_downloaded"
  | "guest_disabled"

export interface ShareEvent {
  eventId: string
  at: string
  type: ShareEventType
  linkId: string | null
  guestId: string | null
  actor: string
  ip: string | null
  targetName: string | null
  detail: string | null
}

export interface EventsPage {
  items: ShareEvent[]
  total: number
  page: number
  size: number
}

const JSON_HEADERS = { "Content-Type": "application/json" }

function json(body: unknown): RequestInit {
  return { headers: JSON_HEADERS, body: JSON.stringify(body) }
}

// --- authenticated ---------------------------------------------------------------------------

export const fetchSharingSettings = () =>
  apiFetchJson<SharingSettings>(
    `${BASE}/settings`,
    undefined,
    "Could not load the sharing settings."
  )

export const saveSharingSettings = (settings: SharingSettings) =>
  apiFetchJson<SharingSettings>(
    `${BASE}/settings`,
    { method: "PUT", ...json(settings) },
    "Could not save the sharing settings."
  )

function targetQuery(targetType: ShareTargetType, targetId: string) {
  return new URLSearchParams({ targetType, targetId }).toString()
}

export const fetchEligibility = (
  targetType: ShareTargetType,
  targetId: string
) =>
  apiFetchJson<Eligibility>(
    `${BASE}/eligibility?${targetQuery(targetType, targetId)}`,
    undefined,
    "Could not check whether this can be shared."
  )

export const fetchLinks = (targetType: ShareTargetType, targetId: string) =>
  apiFetchJson<ShareLink[]>(
    `${BASE}/links?${targetQuery(targetType, targetId)}`,
    undefined,
    "Could not load the share links."
  )

export const fetchAllLinks = (params: {
  status?: LinkListFilter
  page: number
  size: number
}) => {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
  })
  if (params.status) search.set("status", params.status)
  return apiFetchJson<LinksPage>(
    `${BASE}/links/all?${search.toString()}`,
    undefined,
    "Could not load the external links."
  )
}

export const createLink = (request: CreateLinkRequest) =>
  apiFetchJson<LinkCreated>(
    `${BASE}/links`,
    { method: "POST", ...json(request) },
    "Could not create the share link."
  )

export const revokeLink = (linkId: string) =>
  apiFetchVoid(
    `${BASE}/links/${linkId}`,
    { method: "DELETE" },
    "Could not revoke the link."
  )

export const clearLinkReview = (linkId: string) =>
  apiFetchVoid(
    `${BASE}/links/${linkId}/clear-review`,
    { method: "POST" },
    "Could not keep the link."
  )

export const fetchGuests = () =>
  apiFetchJson<Guest[]>(
    `${BASE}/guests`,
    undefined,
    "Could not load the guest accounts."
  )

export const inviteGuest = (email: string, displayName?: string) =>
  apiFetchJson<Guest>(
    `${BASE}/guests/invite`,
    { method: "POST", ...json({ email, displayName }) },
    "Could not invite the guest."
  )

export const setGuestStatus = (
  guestId: string,
  status: "active" | "disabled"
) =>
  apiFetchJson<Guest>(
    `${BASE}/guests/${guestId}`,
    { method: "PATCH", ...json({ status }) },
    "Could not update the guest."
  )

export const deleteGuest = (guestId: string) =>
  apiFetchVoid(
    `${BASE}/guests/${guestId}`,
    { method: "DELETE" },
    "Could not remove the guest."
  )

export const fetchGuestGrants = (
  targetType: ShareTargetType,
  targetId: string
) =>
  apiFetchJson<GuestGrant[]>(
    `${BASE}/guest-grants?${targetQuery(targetType, targetId)}`,
    undefined,
    "Could not load who has guest access."
  )

export const createGuestGrant = (request: CreateGrantRequest) =>
  apiFetchJson<GuestGrant>(
    `${BASE}/guest-grants`,
    { method: "POST", ...json(request) },
    "Could not give the guest access."
  )

export const updateGuestGrant = (
  grantId: string,
  patch: { access?: ShareAccess; expiresAt?: string | null }
) =>
  apiFetchJson<GuestGrant>(
    `${BASE}/guest-grants/${grantId}`,
    { method: "PATCH", ...json(patch) },
    "Could not update the guest's access."
  )

export const deleteGuestGrant = (grantId: string) =>
  apiFetchVoid(
    `${BASE}/guest-grants/${grantId}`,
    { method: "DELETE" },
    "Could not remove the guest's access."
  )

export const setConfidential = (
  targetType: ShareTargetType,
  targetId: string,
  confidential: boolean,
  reason?: string
) =>
  apiFetchJson<{ flaggedLinks: number; revokedLinks: number }>(
    `${BASE}/confidential`,
    {
      method: "PUT",
      ...json({ targetType, targetId, confidential, reason }),
    },
    "Could not change the confidential flag."
  )

export const fetchShareEvents = (params: {
  targetType?: ShareTargetType
  targetId?: string
  linkId?: string
  page: number
  size: number
}) => {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
  })
  if (params.targetType) search.set("targetType", params.targetType)
  if (params.targetId) search.set("targetId", params.targetId)
  if (params.linkId) search.set("linkId", params.linkId)
  return apiFetchJson<EventsPage>(
    `${BASE}/events?${search.toString()}`,
    undefined,
    "Could not load the sharing activity."
  )
}

// --- public (no session) ---------------------------------------------------------------------

export type PublicLinkStatus = LinkStatus | "unavailable"

export interface LinkInfo {
  status: PublicLinkStatus
  passwordRequired: boolean
  targetType?: ShareTargetType
  name?: string
  access?: ShareAccess
  expiresAt?: string | null
  organisation?: string
  sharedBy?: string
  lockedUntil?: string | null
}

export interface OpenResult {
  ok: boolean
  status: PublicLinkStatus | "wrong_password"
  attemptsRemaining?: number
  lockedUntil?: string
  accessToken?: string
  expiresInSeconds?: number
}

export interface ListingItem {
  type: "document" | "folder"
  id: string
  name: string
  mimeType?: string
  sizeBytes?: number
  updatedAt?: string
}

export interface Listing {
  folder: { id: string; name: string }
  breadcrumb: { id: string; name: string }[]
  items: ListingItem[]
}

/** A plain fetch that never throws on HTTP status - public screens render the answer, whatever it is. */
async function publicJson<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch {
    throw new Error("Connection lost. Please try again.")
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message ?? "Something went wrong. Please try again.")
  }
  return response.json() as Promise<T>
}

export const fetchLinkInfo = (token: string) =>
  publicJson<LinkInfo>(`${PUBLIC_SHARE}/${encodeURIComponent(token)}`)

export const openLink = (token: string, password?: string) =>
  publicJson<OpenResult>(`${PUBLIC_SHARE}/${encodeURIComponent(token)}/open`, {
    method: "POST",
    ...json({ password }),
  })

export const fetchLinkListing = (
  token: string,
  accessToken: string,
  folderId?: string
) => {
  const search = new URLSearchParams({ access: accessToken })
  if (folderId) search.set("folderId", folderId)
  return publicJson<Listing>(
    `${PUBLIC_SHARE}/${encodeURIComponent(token)}/items?${search.toString()}`
  )
}

/** URL of a shared document's bytes - for an iframe/img src or a download link (headers can't be sent there). */
export function linkContentUrl(
  token: string,
  accessToken: string,
  opts: { documentId?: string; download?: boolean } = {}
): string {
  const search = new URLSearchParams({
    access: accessToken,
    download: String(!!opts.download),
  })
  if (opts.documentId) search.set("documentId", opts.documentId)
  return `${PUBLIC_SHARE}/${encodeURIComponent(token)}/content?${search.toString()}`
}

// --- guest portal ----------------------------------------------------------------------------

export interface PortalLogin {
  ok: boolean
  status: "ok" | "invalid" | "disabled" | "locked" | "choose_organisation"
  token?: string
  expiresInSeconds?: number
  organisations?: { tenantId: string; name: string }[]
}

export interface PortalItem extends ListingItem {
  access: ShareAccess
}

export interface PortalListing {
  breadcrumb: { id: string; name: string }[]
  items: PortalItem[]
}

export const fetchInvite = (token: string) =>
  publicJson<{ email: string; organisation: string; valid: boolean }>(
    `${PUBLIC_PORTAL}/invite/${encodeURIComponent(token)}`
  )

export const acceptInvite = (token: string, password: string) =>
  publicJson<{ ok: boolean }>(`${PUBLIC_PORTAL}/accept-invite`, {
    method: "POST",
    ...json({ token, password }),
  })

export const portalLogin = (
  email: string,
  password: string,
  tenantId?: string
) =>
  publicJson<PortalLogin>(`${PUBLIC_PORTAL}/login`, {
    method: "POST",
    ...json({ email, password, tenantId }),
  })

const guestHeaders = (token: string) => ({ "X-Guest-Token": token })

export const fetchPortalMe = (token: string) =>
  publicJson<{ email: string; displayName: string; organisation: string }>(
    `${PUBLIC_PORTAL}/me`,
    { headers: guestHeaders(token) }
  )

export const fetchPortalItems = (token: string, parentFolderId?: string) =>
  publicJson<PortalListing>(
    `${PUBLIC_PORTAL}/items${
      parentFolderId
        ? `?${new URLSearchParams({ parentFolderId }).toString()}`
        : ""
    }`,
    { headers: guestHeaders(token) }
  )

export function portalContentUrl(
  token: string,
  documentId: string,
  download: boolean
): string {
  return `${PUBLIC_PORTAL}/content?${new URLSearchParams({
    documentId,
    download: String(download),
    access: token,
  }).toString()}`
}

// --- helpers ---------------------------------------------------------------------------------

/** Copy for each way a link can be unusable - shown as a calm access-denied screen, never an error. */
export function deniedCopy(
  status: PublicLinkStatus | "wrong_password"
): { title: string; body: string } {
  switch (status) {
    case "expired":
      return {
        title: "This link has expired",
        body: "The time for this shared link has passed. Ask the person who sent it to share it with you again.",
      }
    case "revoked":
      return {
        title: "This link is no longer available",
        body: "The person who shared it has turned it off. Ask them to send you a new one if you still need access.",
      }
    case "exhausted":
      return {
        title: "This link has reached its view limit",
        body: "It could only be opened a set number of times. Ask the person who sent it to share it again.",
      }
    case "locked":
      return {
        title: "Too many wrong passwords",
        body: "This link is paused for a short while. Try again later.",
      }
    default:
      return {
        title: "This link isn't available",
        body: "It may have been mistyped or removed. Check the address, or ask the person who sent it to share it again.",
      }
  }
}

/** Badge wording and tone for a link's status. */
export function linkStatusLabel(link: Pick<ShareLink, "status" | "needsReview">): {
  label: string
  variant: "default" | "muted" | "gold" | "rust"
} {
  if (link.needsReview && link.status === "active")
    return { label: "Needs review", variant: "gold" }
  switch (link.status) {
    case "active":
      return { label: "Active", variant: "default" }
    case "locked":
      return { label: "Locked", variant: "gold" }
    case "revoked":
      return { label: "Revoked", variant: "rust" }
    case "expired":
      return { label: "Expired", variant: "muted" }
    case "exhausted":
      return { label: "View limit reached", variant: "muted" }
  }
}

/** A readable sentence for an activity row. */
export function eventLabel(type: ShareEventType): string {
  const labels: Record<ShareEventType, string> = {
    link_created: "Created a share link",
    link_opened: "Opened a link",
    link_viewed: "Viewed via link",
    link_downloaded: "Downloaded via link",
    link_revoked: "Revoked a link",
    link_expired: "Link expired",
    password_failed: "Wrong password entered",
    link_locked: "Link locked after wrong passwords",
    link_flagged: "Link flagged for review",
    link_review_cleared: "Link kept after review",
    guest_invited: "Invited a guest",
    guest_grant_created: "Gave a guest access",
    guest_grant_revoked: "Removed a guest's access",
    guest_signed_in: "Guest signed in",
    guest_viewed: "Guest viewed",
    guest_downloaded: "Guest downloaded",
    guest_disabled: "Guest account disabled",
  }
  return labels[type] ?? type
}

/** `YYYY-MM-DDTHH:mm` (local, what DateTimePicker produces) for N days from now. */
export function defaultExpiryValue(days: number, from = new Date()): string {
  const d = new Date(from.getTime() + days * 86_400_000)
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Local `YYYY-MM-DDTHH:mm` to the ISO instant the backend expects; undefined when blank. */
export function toIsoInstant(local: string): string | undefined {
  if (!local) return undefined
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

export function isPreviewableInline(mimeType?: string): boolean {
  if (!mimeType) return true
  return (
    mimeType === "application/pdf" ||
    mimeType.startsWith("image/") ||
    mimeType.startsWith("text/") ||
    // Office files are served as their PDF rendition when download=false (contract).
    mimeType.includes("officedocument") ||
    mimeType.includes("msword") ||
    mimeType.includes("ms-excel") ||
    mimeType.includes("ms-powerpoint")
  )
}

/** The guest token lives in sessionStorage only - closing the tab signs the guest out. */
export const GUEST_TOKEN_KEY = "dms_guest_token"
