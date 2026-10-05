"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchDocumentWorkflowInstances } from "./api"

/** A document's workflow runs, newest first - the first page is plenty for the document page's short history. */
export function useDocumentWorkflowHistory(documentId: string, size = 20) {
  return useQuery({
    queryKey: ["workflow", "document", documentId, "instances", size],
    queryFn: () => fetchDocumentWorkflowInstances(documentId, 0, size),
    enabled: Boolean(documentId),
    retry: false,
  })
}
