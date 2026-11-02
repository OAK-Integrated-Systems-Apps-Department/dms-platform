import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { ApiError } from "@/lib/api-error"

import type { DownloadedFile } from "@/lib/download-file"
import type {
  BatchSummaryDto,
  BulkPreviewDto,
  BulkTransferRequestDto,
  DocumentOwnershipInfoDto,
  HistoryExportFilters,
  OffboardRequestDto,
  OffboardResponseDto,
  OffboardingPreviewDto,
  OwnedDocumentsDto,
  OwnedDocumentsFilters,
  OwnershipHistoryPageDto,
  SkippedDocumentsDto,
  TransferDocumentRequestDto,
  TransferDocumentResponseDto,
  TransferPreviewDto,
  WorkFlagsDto,
} from "@/lib/ownership/types"

const OWNERSHIP = `${API_BASE_URL}/api/v1/ownership`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** A transfer run moves at most this many documents; the screens split bigger sets. */
export const MAX_TRANSFER_DOCUMENTS = 5000

function post(body: unknown): RequestInit {
  return { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(body) }
}

export async function fetchDocumentOwnership(documentId: string): Promise<DocumentOwnershipInfoDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/ownership`,
    undefined,
    "Could not load the document's owner."
  )
}

export async function transferDocument(
  documentId: string,
  body: TransferDocumentRequestDto
): Promise<TransferDocumentResponseDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/ownership/transfer`,
    post(body),
    "Could not transfer the document."
  )
}

/** What a single transfer would do, writing nothing - the dialog's confirmation step. */
export async function previewDocumentTransfer(
  documentId: string,
  body: TransferDocumentRequestDto
): Promise<TransferPreviewDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/ownership/transfer/preview`,
    post(body),
    "Could not check the transfer."
  )
}

function listParams(filters: OwnedDocumentsFilters, page: number, size: number): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.userId) params.set("userId", filters.userId)
  if (filters.departmentId) params.set("departmentId", filters.departmentId)
  if (filters.q) params.set("q", filters.q)
  if (filters.state) params.set("state", filters.state)
  if (filters.recycleBin !== undefined) params.set("recycleBin", String(filters.recycleBin))
  params.set("page", String(page))
  params.set("size", String(size))
  return params
}

export async function fetchOwnedDocuments(
  filters: OwnedDocumentsFilters,
  page: number,
  size: number
): Promise<OwnedDocumentsDto> {
  return apiFetchJson(
    `${OWNERSHIP}/documents?${listParams(filters, page, size).toString()}`,
    undefined,
    "Could not load the owned documents."
  )
}

export async function fetchPendingDocuments(
  filters: Pick<OwnedDocumentsFilters, "departmentId" | "q">,
  page: number,
  size: number
): Promise<OwnedDocumentsDto> {
  const params = listParams(filters, page, size)
  return apiFetchJson(`${OWNERSHIP}/pending?${params.toString()}`, undefined, "Could not load the pending documents.")
}

export async function transferBulk(body: BulkTransferRequestDto): Promise<BatchSummaryDto> {
  return apiFetchJson(`${OWNERSHIP}/transfers`, post(body), "Could not transfer the documents.")
}

/** What a bulk transfer would do, writing nothing. */
export async function previewBulkTransfer(body: BulkTransferRequestDto): Promise<BulkPreviewDto> {
  return apiFetchJson(`${OWNERSHIP}/transfers/preview`, post(body), "Could not check the transfer.")
}

export async function fetchBatch(batchId: string): Promise<BatchSummaryDto> {
  return apiFetchJson(`${OWNERSHIP}/batches/${batchId}`, undefined, "Could not load the transfer.")
}

export async function fetchOffboardingPreview(userId: string): Promise<OffboardingPreviewDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/offboarding-preview`,
    undefined,
    "Could not load what offboarding this user involves."
  )
}

export async function offboardUser(userId: string, body: OffboardRequestDto): Promise<OffboardResponseDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/users/${userId}/offboard`, post(body), "Could not offboard the user.")
}

export async function fetchOwnershipHistory(
  documentId: string,
  page: number,
  size: number
): Promise<OwnershipHistoryPageDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/ownership-history?page=${page}&size=${size}`,
    undefined,
    "Could not load the ownership history."
  )
}

/** `GET /api/v1/ownership/history/export` - the filtered history as CSV. */
export async function downloadHistoryCsv(filters: HistoryExportFilters): Promise<DownloadedFile> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value)
  }
  const query = params.toString()
  const response = await apiFetch(
    `${OWNERSHIP}/history/export${query ? `?${query}` : ""}`,
    undefined,
    "Could not export the ownership history."
  )
  return { blob: await response.blob(), filename: "ownership-history.csv" }
}

/** The 422 a transfer or offboarding gets above 5,000 documents; `total` is how many were asked for. */
export function tooLargeTotal(error: unknown): number | null {
  if (error instanceof ApiError && error.code === "OWNERSHIP_BATCH_TOO_LARGE") {
    const details = error.details as { total?: number } | undefined
    return typeof details?.total === "number" ? details.total : MAX_TRANSFER_DOCUMENTS + 1
  }
  return null
}

/** The 409 offboarding answers when the stuck-task number wasn't confirmed: the flagged work comes back as details. */
export function confirmationFlagsOf(error: unknown): WorkFlagsDto | null {
  if (error instanceof ApiError && error.code === "OFFBOARD_CONFIRMATION_REQUIRED") {
    return (error.details as WorkFlagsDto | undefined) ?? null
  }
  return null
}

/** Plain-language reasons for skipped documents. */
export const SKIP_REASON_LABELS: Record<string, string> = {
  not_found: "Not found or not available to you",
  purged: "Permanently deleted",
  already_owner: "Already owned that way",
  out_of_scope: "Owned by someone outside your departments",
}

/**
 * Runs "transfer all" in steps of at most 5,000 until nothing is left, reporting progress after each step.
 * Transferred documents leave the source's list, so repeating the same request converges. Stops on the first
 * empty step or failure (the failure is rethrown with the totals so far in `done`).
 */
export async function transferAllInSteps(
  request: Omit<BulkTransferRequestDto, "mode" | "limit" | "documentIds">,
  onProgress?: (done: number, last: BatchSummaryDto) => void
): Promise<{ done: number; batches: BatchSummaryDto[] }> {
  const batches: BatchSummaryDto[] = []
  let done = 0
  while (true) {
    const batch = await transferBulk({ ...request, mode: "all", limit: MAX_TRANSFER_DOCUMENTS })
    batches.push(batch)
    done += batch.transferredCount
    onProgress?.(done, batch)
    // A short step means the source is empty; an unproductive full step would loop forever, so stop there too.
    if (batch.totalCount < MAX_TRANSFER_DOCUMENTS || batch.transferredCount === 0) break
  }
  return { done, batches }
}

/** One summary for several steps of the same transfer: counts add up, skipped groups merge by reason. */
export function mergeBatches(batches: BatchSummaryDto[]): BatchSummaryDto {
  if (batches.length === 1) return batches[0]
  const last = batches[batches.length - 1]
  const skipped = new Map<string, SkippedDocumentsDto>()
  for (const batch of batches) {
    for (const group of batch.skipped) {
      const existing = skipped.get(group.reason)
      skipped.set(group.reason, {
        reason: group.reason,
        count: (existing?.count ?? 0) + group.count,
        documentIds: [...(existing?.documentIds ?? []), ...group.documentIds].slice(0, 100),
      })
    }
  }
  const sum = (pick: (b: BatchSummaryDto) => number) => batches.reduce((total, b) => total + pick(b), 0)
  return {
    ...last,
    totalCount: sum((b) => b.totalCount),
    transferredCount: sum((b) => b.transferredCount),
    skippedCount: sum((b) => b.skippedCount),
    skipped: [...skipped.values()],
    accessGrantedCount: sum((b) => b.accessGrantedCount),
    convertedToRestrictedCount: sum((b) => b.convertedToRestrictedCount),
    flaggedTaskCount: sum((b) => b.flaggedTaskCount),
    newOwnerLacksViewPermission: batches.some((b) => b.newOwnerLacksViewPermission),
  }
}
