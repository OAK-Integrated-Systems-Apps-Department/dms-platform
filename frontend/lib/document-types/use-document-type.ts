"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  fetchCurrentDocumentTypeFields,
  replaceDocumentTypeFieldLabelAliases,
  replaceDocumentTypeFields,
  type DocumentTypeFieldMappingItemPayload,
  type DocumentTypeFieldResponseDto,
} from "@/lib/document-type-fields/api"

import { fetchDocumentType, updateDocumentType, type UpdateDocumentTypePayload } from "./api"

/**
 * Loads one document type plus its current field mapping — the Edit
 * Document Type screen's "Fields" tab needs both together.
 */
export function useDocumentType(documentTypeId: string) {
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
    enabled: Boolean(documentTypeId),
  })

  const updateTypeMutation = useMutation({
    mutationFn: (payload: UpdateDocumentTypePayload) => updateDocumentType(documentTypeId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["document-type", documentTypeId] })
      queryClient.invalidateQueries({ queryKey: ["document-types"] })
    },
  })

  const replaceFieldsMutation = useMutation({
    mutationFn: (fields: DocumentTypeFieldMappingItemPayload[]) => replaceDocumentTypeFields(documentTypeId, fields),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["document-type", documentTypeId] })
      toast.success("Field mapping published.")
    },
  })

  const saveLabelAliasesMutation = useMutation({
    mutationFn: ({ fieldId, labelAliases }: { fieldId: string; labelAliases: string[] }) =>
      replaceDocumentTypeFieldLabelAliases(documentTypeId, fieldId, labelAliases),
    // Patches the one row's hints into the cached mapping so the saved list
    // shows the moment the PUT returns, THEN invalidates that same key. The
    // patch alone isn't enough: a GET of the mapping already in flight when
    // the PUT resolves (a window-focus refetch — this QueryClient keeps the
    // staleTime 0 / refetchOnWindowFocus defaults — or the refetch a Publish
    // just triggered) may have been answered before the PUT committed, and
    // would land afterwards and overwrite the patch with the old hints. The
    // Fields tab would then show the save as undone, and the next wholesale
    // PUT from that row would silently delete what was just stored.
    // invalidateQueries cancels such an in-flight fetch (cancelRefetch
    // defaults to true) and starts a fresh one, which can only be answered
    // after the commit — so the last response the cache sees is never older
    // than the save. Not awaited, so "Saving…" doesn't wait on that refetch.
    // The Fields tab keys its "reset my unpublished edits" logic on the
    // mapping's own content, not the array reference, precisely so neither
    // this patch nor that refetch (which only differ in hints) can wipe
    // mapping edits the user hasn't published yet.
    onSuccess: (saved, { fieldId }) => {
      const fieldsKey = ["document-type", documentTypeId, "fields"]
      queryClient.setQueryData<DocumentTypeFieldResponseDto[]>(fieldsKey, (current) =>
        current?.map((field) => (field.fieldId === fieldId ? { ...field, labelAliases: saved.labelAliases } : field))
      )
      void queryClient.invalidateQueries({ queryKey: fieldsKey, exact: true })
    },
  })

  return {
    documentType: typeQuery.data,
    loading: typeQuery.isLoading,
    notFound: typeQuery.isError,
    fields: fieldsQuery.data ?? [],
    fieldsLoading: fieldsQuery.isLoading,
    updateType: async (payload: UpdateDocumentTypePayload): Promise<string | null> => {
      try {
        await updateTypeMutation.mutateAsync(payload)
        return null
      } catch (err) {
        return err instanceof Error ? err.message : "Could not save changes."
      }
    },
    replaceFields: async (fields: DocumentTypeFieldMappingItemPayload[]): Promise<string | null> => {
      try {
        await replaceFieldsMutation.mutateAsync(fields)
        return null
      } catch (err) {
        return err instanceof Error ? err.message : "Could not save the field mapping."
      }
    },
    /** Saves one field's type-scoped OCR label hints on their own (no new schema version) — resolves to an error message, or `null` on success. */
    saveFieldLabelAliases: async (fieldId: string, labelAliases: string[]): Promise<string | null> => {
      try {
        await saveLabelAliasesMutation.mutateAsync({ fieldId, labelAliases })
        return null
      } catch (err) {
        return err instanceof Error ? err.message : "Could not save this field's hints for this document type."
      }
    },
  }
}
