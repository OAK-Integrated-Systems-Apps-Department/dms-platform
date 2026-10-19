"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchDocumentWorkflowStatuses, type DocumentWorkflowStatusDto } from "./api"

/**
 * The newest workflow run of each document on the page, in one call - the
 * "In approval / Approved / Rejected" badges in My Documents. Documents with
 * no run (and documents the caller can't open) are simply absent from the map.
 */
export function useDocumentWorkflowStatuses(documentIds: string[], enabled = true) {
  // Order doesn't matter to the server, and a stable key keeps the cache warm while a page re-renders.
  const ids = [...documentIds].sort()
  return useQuery<DocumentWorkflowStatusDto[], Error, Map<string, DocumentWorkflowStatusDto>>({
    queryKey: ["workflow", "document-status", ids],
    queryFn: () => fetchDocumentWorkflowStatuses(ids),
    enabled: enabled && ids.length > 0,
    select: (rows) => new Map(rows.map((row) => [row.documentId, row])),
    staleTime: 30_000,
    retry: false,
  })
}
