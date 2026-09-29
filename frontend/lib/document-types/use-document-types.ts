"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { activateDocumentType, deactivateDocumentType, fetchAllDocumentTypes } from "./api"

/**
 * Loads every document type in the caller's tenant for the Document Types
 * list screen. `enabled` (default true) skips the fetch for a caller with
 * neither view nor update — matching the Roles List page's `canView`-gated
 * fetch.
 */
export function useDocumentTypes(enabled = true) {
  const query = useQuery({
    queryKey: ["document-types"],
    queryFn: fetchAllDocumentTypes,
    retry: false,
    enabled,
  })

  const queryClient = useQueryClient()

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["document-types"] })
  }

  const activateMutation = useMutation({
    mutationFn: (documentTypeId: string) => activateDocumentType(documentTypeId),
    onSuccess: () => {
      invalidate()
      toast.success("Document type activated.")
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not activate this document type."),
  })

  const deactivateMutation = useMutation({
    mutationFn: (documentTypeId: string) => deactivateDocumentType(documentTypeId),
    onSuccess: () => {
      invalidate()
      toast.success("Document type deactivated.")
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not deactivate this document type."),
  })

  return {
    ...query,
    documentTypes: query.data ?? [],
    activate: (documentTypeId: string) => activateMutation.mutate(documentTypeId),
    deactivate: (documentTypeId: string) => deactivateMutation.mutate(documentTypeId),
  }
}
