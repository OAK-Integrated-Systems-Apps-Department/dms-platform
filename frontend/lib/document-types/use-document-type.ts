"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  fetchCurrentDocumentTypeFields,
  replaceDocumentTypeFields,
  type DocumentTypeFieldMappingItemPayload,
} from "@/lib/document-type-fields/api"

import {
  fetchDocumentType,
  updateDocumentType,
  type UpdateDocumentTypePayload,
} from "./api"

/**
 * Loads one document type plus its current field mapping — the Edit
 * Document Type screen's "Fields" tab needs both together.
 */
export function useDocumentType(
  documentTypeId: string,
  /** Load the field mapping too - only for callers allowed to see it. */
  { withFields = true }: { withFields?: boolean } = {}
) {
  const queryClient = useQueryClient()

  const typeQuery = useQuery({
    queryKey: ["document-type", documentTypeId],
    queryFn: () => fetchDocumentType(documentTypeId),
    retry: false,
    enabled: Boolean(documentTypeId),
  })

  const fieldsQuery = useQuery({
    queryKey: ["document-type", documentTypeId, "fields"],
    queryFn: () => fetchCurrentDocumentTypeFields(documentTypeId),
    retry: false,
    enabled: Boolean(documentTypeId) && withFields,
  })

  const updateTypeMutation = useMutation({
    mutationFn: (payload: UpdateDocumentTypePayload) =>
      updateDocumentType(documentTypeId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["document-type", documentTypeId],
      })
      queryClient.invalidateQueries({ queryKey: ["document-types"] })
    },
  })

  const replaceFieldsMutation = useMutation({
    mutationFn: (fields: DocumentTypeFieldMappingItemPayload[]) =>
      replaceDocumentTypeFields(documentTypeId, fields),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["document-type", documentTypeId],
      })
      toast.success("Field mapping published.")
    },
  })

  return {
    documentType: typeQuery.data,
    loading: typeQuery.isLoading,
    notFound: typeQuery.isError,
    fields: fieldsQuery.data ?? [],
    fieldsLoading: fieldsQuery.isLoading,
    updateType: async (
      payload: UpdateDocumentTypePayload
    ): Promise<string | null> => {
      try {
        await updateTypeMutation.mutateAsync(payload)
        return null
      } catch (err) {
        return err instanceof Error ? err.message : "Could not save changes."
      }
    },
    replaceFields: async (
      fields: DocumentTypeFieldMappingItemPayload[]
    ): Promise<string | null> => {
      try {
        await replaceFieldsMutation.mutateAsync(fields)
        return null
      } catch (err) {
        return err instanceof Error
          ? err.message
          : "Could not save the field mapping."
      }
    },
  }
}
