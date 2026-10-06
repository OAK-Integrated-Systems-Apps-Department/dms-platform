import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** The periods the dashboard offers, in days ending today. */
export const DASHBOARD_PERIODS = [7, 30, 90] as const
export type DashboardPeriod = (typeof DASHBOARD_PERIODS)[number]

/** Mirrors backend/dms-core's DashboardResponse - only what the caller may see. */
export interface DashboardDto {
  days: DashboardPeriod
  /** False when the caller can't view documents - every document figure is empty. */
  documentsVisible: boolean
  documents: {
    total: number
    /** Added this period, and in the period before it. */
    added: number
    addedBefore: number
    mine: number
    restricted: number
  }
  storage: {
    usedBytes: number
    addedBytes: number
    addedBytesBefore: number
    versions: number
  }
  /** Views, downloads and uploads - the caller's own, or everyone's (`usageScope`). */
  usage: {
    views: number
    downloads: number
    uploads: number
    viewsBefore: number
    downloadsBefore: number
    uploadsBefore: number
  }
  /** Every day of the period, oldest first, zeros included. */
  daily: DashboardDay[]
  types: { name: string; count: number }[]
  /** Largest first, by bytes. */
  formats: { format: DashboardFormat; count: number; bytes: number }[]
  /** Most documents first; `folderId` null = not in a folder. */
  folders: {
    folderId: string | null
    name: string | null
    count: number
    bytes: number
  }[]
  mostOpened: DashboardTopDocument[]
  /** Null without the quarantine-view permission. */
  security: {
    clean: number
    threats: number
    unscannable: number
    refused: number
    quarantined: number
  } | null
  recent: DashboardRecentDocument[]
  usageScope: "mine" | "organisation"
  activity: DashboardActivity[]
}

export interface DashboardDay {
  date: string
  added: number
  /** Documents (that the caller can see) that existed by the end of the day. */
  documents: number
  views: number
  downloads: number
  uploads: number
}

export type DashboardFormat =
  "pdf" | "word" | "sheet" | "slides" | "image" | "archive" | "text" | "other"

export interface DashboardRecentDocument {
  documentId: string
  title: string
  documentTypeName: string | null
  fileName: string | null
  sizeBytes: number | null
  mimeType: string | null
  ownerUserId: string | null
  ownerName: string | null
  createdAt: string
}

export interface DashboardTopDocument {
  documentId: string
  title: string
  documentTypeName: string | null
  fileName: string | null
  sizeBytes: number | null
  mimeType: string | null
  createdAt: string
  views: number
  downloads: number
}

export interface DashboardActivity {
  id: string
  action: string
  objectType: string | null
  objectId: string | null
  resourceName: string | null
  actorId: string | null
  actorName: string | null
  occurredAt: string
}

/** `GET /api/v1/dashboard` - counted in the browser's time zone, so the days are the user's own. */
export async function fetchDashboard(
  days: DashboardPeriod = 30
): Promise<DashboardDto> {
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return apiFetchJson<DashboardDto>(
    `${API_BASE_URL}/api/v1/dashboard?timeZone=${encodeURIComponent(timeZone)}&days=${days}`,
    undefined,
    "Could not load the dashboard."
  )
}
