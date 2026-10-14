"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's DigitalImpactResponse. */
export interface DigitalImpactDto {
  from: string
  to: string
  days: number
  totals: ImpactTotals
  /** The same figures for the period just before, as long. */
  previous: ImpactTotals
  coverage: {
    /** Versions uploaded in the period. */
    versions: number
    /** ...with a page count (counted from the file, or as the file reported it). */
    counted: number
    countedFromFile: number
    reportedByFile: number
    /** ...in formats without pages, or that couldn't be read. */
    noPages: number
    /** ...waiting for the page count job - a few minutes. */
    pending: number
    /** ...never virus-scanned, so they can't be read until they're rescanned. */
    unscanned: number
  }
  /** Every page counted so far, whenever it was added. */
  allTime: ImpactTotals
  /** The period's pages by kind of file, most pages first. */
  sources: { kind: PageKind; pages: number; files: number }[]
  /** The documents whose uploads in the period held the most pages. */
  topDocuments: {
    documentId: string
    title: string
    documentTypeName: string | null
    fileName: string | null
    sizeBytes: number | null
    mimeType: string | null
    createdAt: string
    pages: number
  }[]
  daily: { date: string; pages: number; paperKg: number; co2eKg: number }[]
  method: {
    calculationVersion: string
    paperSize: string
    paperWidthMm: number
    paperHeightMm: number
    paperGsm: number
    pagesPerSheet: number
    gramsPerPage: number
    co2eKgPerKgPaper: number
    co2eSource: string
    waterLitresPerKgPaper: number
    waterSource: string
  }
}

export type PageKind = "pdf" | "word" | "slides" | "scans" | "other"

export interface ImpactTotals {
  /** Measured: pages of the documents processed digitally. */
  pages: number
  /** Calculated from the pages and the configured paper. */
  sheets: number
  paperKg: number
  /** Estimated with the configured factors. */
  co2eKg: number
  waterLitres: number
}

/** The last N days, or a custom range of calendar days (yyyy-mm-dd). */
export type ImpactRange =
  | { kind: "days"; days: 7 | 30 | 90 }
  | { kind: "custom"; from: string; to: string }

/** `GET /api/v1/dashboard/impact` - worked out on the server, in the browser's time zone. */
export async function fetchImpact(
  range: ImpactRange
): Promise<DigitalImpactDto> {
  const params = new URLSearchParams({
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  })
  if (range.kind === "days") params.set("days", String(range.days))
  else {
    params.set("from", range.from)
    params.set("to", range.to)
  }
  return apiFetchJson<DigitalImpactDto>(
    `${API_BASE_URL}/api/v1/dashboard/impact?${params.toString()}`,
    undefined,
    "Could not load the Environmental Impact figures."
  )
}

export function useImpact(range: ImpactRange) {
  return useQuery({
    queryKey: ["dashboard", "impact", range],
    queryFn: () => fetchImpact(range),
    staleTime: 60_000,
    // Uploads, deletes and restores happen on other pages - always refresh on arrival.
    refetchOnMount: "always",
    placeholderData: keepPreviousData,
    retry: false,
  })
}
