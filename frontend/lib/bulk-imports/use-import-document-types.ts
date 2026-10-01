"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchImportDocumentTypes } from "./api"

/**
 * Document types a bulk import can target (active only - filtered
 * server-side). Keyed outside `["bulk-imports"]` so batch mutations,
 * which invalidate that prefix, don't refetch it.
 */
export function useImportDocumentTypes(enabled = true) {
  const query = useQuery({
    queryKey: ["import-document-types"],
    queryFn: fetchImportDocumentTypes,
    retry: false,
    enabled,
  })
  return { ...query, documentTypes: query.data ?? [] }
}
