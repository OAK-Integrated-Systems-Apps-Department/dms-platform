import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors PlatformOverviewResponse.Totals. */
export interface PlatformTotalsDto {
  organisations: number
  activeOrganisations: number
  suspendedOrganisations: number
  deactivatedOrganisations: number
  documents: number
  storageBytes: number
  users: number
}

/** Mirrors PlatformOverviewResponse.Organisation - what one client is using now. */
export interface PlatformOrganisationDto {
  tenantId: string
  tenantName: string
  domain: string
  subscriptionTier: string
  /** active | suspended | archived */
  status: string
  deactivated: boolean
  documents: number
  storageBytes: number
  users: number
  createdAt: string
  /** Versions stored in the last 30 days - a busy client vs a dormant one. */
  documentsAddedLast30Days: number
  /** When anything was last stored. Null if this organisation has never uploaded. */
  lastActivityAt: string | null
}

/** One month of platform-wide intake. Quiet months are present with zeros, never missing. */
export interface PlatformMonthDto {
  /** YYYY-MM */
  month: string
  documents: number
  storageBytes: number
}

export interface PlatformOverviewDto {
  totals: PlatformTotalsDto
  organisations: PlatformOrganisationDto[]
  /** What the whole installation's storage is made of, largest family first. */
  fileTypes: PlatformFileFamilyDto[]
  /** Last 12 months, oldest first. */
  monthly: PlatformMonthDto[]
}

/**
 * Every organisation and what it is using, via `GET /api/v1/platform/overview`.
 *
 * Platform admins only, and works without tenant context - which is the
 * point: the normal dashboard is tenant-scoped and answers 401
 * TENANT_CONTEXT_MISSING for an account that belongs to no tenant.
 */
export async function fetchPlatformOverview(): Promise<PlatformOverviewDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/platform/overview`,
    undefined,
    "Could not load the platform overview."
  )
}

/** One file family's share of storage - of one organisation, or of the whole installation. */
export interface PlatformFileFamilyDto {
  /** "PDF", "Word", "Image", "Archive", "Other" - the vocabulary the document list uses. */
  label: string
  documents: number
  storageBytes: number
}

/** Documents at one lifecycle stage. Every stage is present, at zero if unused. */
export interface PlatformStatusCountDto {
  /** draft | published | archived | pending-deletion */
  status: string
  documents: number
}

/** Mirrors PlatformOrganisationBreakdown. */
export interface PlatformOrganisationBreakdownDto {
  fileTypes: PlatformFileFamilyDto[]
  /** In lifecycle order, never reordered by size. */
  statuses: PlatformStatusCountDto[]
  /** This organisation's last 12 months, oldest first, quiet months zero-filled. */
  monthly: PlatformMonthDto[]
}

/**
 * One organisation in detail, via
 * `GET /api/v1/platform/organisations/{tenantId}/breakdown`.
 *
 * Fetched when a dashboard row is opened rather than with the overview: two
 * extra queries per organisation would otherwise run for every row nobody
 * looks at.
 */
export async function fetchPlatformOrganisationBreakdown(
  tenantId: string
): Promise<PlatformOrganisationBreakdownDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/platform/organisations/${tenantId}/breakdown`,
    undefined,
    "Could not load this organisation's breakdown."
  )
}
