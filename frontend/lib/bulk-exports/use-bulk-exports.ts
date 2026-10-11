"use client"

import { useEffect, useRef } from "react"
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { fetchBulkImports } from "@/lib/bulk-imports/api"

import {
  createExport,
  fetchExportDocumentTypes,
  fetchExportableDocuments,
  fetchMyExports,
  isPendingStatus,
  previewExport,
  type BulkExportFiltersDto,
  type BulkExportSelectionDto,
  type ExportJobStatus,
  type MetadataFormat,
} from "./api"

export const BULK_EXPORTS_KEY = ["bulk-exports"] as const
const MY_EXPORTS_KEY = [...BULK_EXPORTS_KEY, "mine"] as const
const POLL_INTERVAL_MS = 3000

/** Active document types for the type filter - gated on the export permissions, not the import/metadata ones. */
export function useExportDocumentTypes(enabled = true) {
  const query = useQuery({
    queryKey: ["bulk-export-document-types"],
    queryFn: fetchExportDocumentTypes,
    retry: false,
    enabled,
  })
  return { ...query, documentTypes: query.data ?? [] }
}

/** One page (1-based) of the documents matching `filters`, keeping the previous page on screen while the next loads. */
export function useExportableDocuments(filters: BulkExportFiltersDto, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: [...BULK_EXPORTS_KEY, "documents", filters, page, size],
    queryFn: () => fetchExportableDocuments(filters, page - 1, size),
    placeholderData: keepPreviousData,
    retry: false,
    enabled,
  })
}

/**
 * The caller's recent imports, for the import-batch filter. Only for a
 * caller holding the import permission (the endpoint is gated on it);
 * keyed outside `["bulk-imports"]` like the other import lookups.
 */
export function useImportBatchOptions(enabled: boolean) {
  const query = useQuery({
    queryKey: ["bulk-export-import-batches"],
    queryFn: () => fetchBulkImports(0, 50),
    retry: false,
    enabled,
  })
  return { ...query, batches: query.data?.content ?? [] }
}

export function usePreviewExport() {
  return useMutation({ mutationFn: (selection: BulkExportSelectionDto) => previewExport(selection) })
}

export function useCreateExport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ selection, format }: { selection: BulkExportSelectionDto; format: MetadataFormat }) =>
      createExport(selection, format),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: MY_EXPORTS_KEY }),
  })
}

/**
 * One page (1-based) of the caller's exports, newest first. Polls while
 * any export on it is queued or being built, and toasts when one the page
 * has seen pending finishes - never for what was already finished on
 * first load.
 */
export function useMyExports(page: number, size: number) {
  const query = useQuery({
    queryKey: [...MY_EXPORTS_KEY, page, size],
    queryFn: () => fetchMyExports(page - 1, size),
    placeholderData: keepPreviousData,
    retry: false,
    refetchInterval: (q) => (q.state.data?.content.some((job) => isPendingStatus(job.status)) ? POLL_INTERVAL_MS : false),
  })

  const seen = useRef(new Map<string, ExportJobStatus>())
  useEffect(() => {
    for (const job of query.data?.content ?? []) {
      const previous = seen.current.get(job.exportJobId)
      if (isPendingStatus(previous) && !isPendingStatus(job.status)) {
        if (job.status === "completed") toast.success("Your export is ready")
        else if (job.status === "failed") toast.error("Export failed", job.failureReason ? { description: job.failureReason } : undefined)
      }
      seen.current.set(job.exportJobId, job.status)
    }
  }, [query.data])

  return query
}
