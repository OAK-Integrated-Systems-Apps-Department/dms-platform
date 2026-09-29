"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  activateMetadataField,
  deactivateMetadataField,
  deleteMetadataField as deleteMetadataFieldRequest,
  deprecateMetadataField,
  fetchAllMetadataFields,
} from "./api"

/**
 * Loads every metadata field in the caller's tenant for the Metadata Fields
 * list screen. `enabled` (default true) lets a caller skip the fetch for a
 * caller with neither view-field nor update-field — matching the Roles List
 * page's own `canView`-gated fetch, rather than letting this 403 for them.
 */
export function useMetadataFields(enabled = true) {
  const query = useQuery({
    queryKey: ["metadata-fields"],
    queryFn: fetchAllMetadataFields,
    retry: false,
    enabled,
  })

  const queryClient = useQueryClient()

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["metadata-fields"] })
  }

  const activateMutation = useMutation({
    mutationFn: (fieldId: string) => activateMetadataField(fieldId),
    onSuccess: () => {
      invalidate()
      toast.success("Field activated.")
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not activate this field."),
  })

  const deactivateMutation = useMutation({
    mutationFn: (fieldId: string) => deactivateMetadataField(fieldId),
    onSuccess: () => {
      invalidate()
      toast.success("Field deactivated.")
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not deactivate this field."),
  })

  // Deprecate and delete are both driven from a confirmation dialog
  // (FieldDeprecateDialog / a delete confirm) that reports success/failure
  // inline the same way RoleDeleteDialog does, so these deliberately don't
  // toast on error themselves — only on success, once the dialog has
  // already closed.
  const deprecateMutation = useMutation({
    mutationFn: (fieldId: string) => deprecateMetadataField(fieldId),
    onSuccess: () => {
      invalidate()
      toast.success("Field deprecated.")
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (fieldId: string) => deleteMetadataFieldRequest(fieldId),
    onSuccess: () => {
      invalidate()
      toast.success("Field deleted.")
    },
  })

  return {
    ...query,
    fields: query.data ?? [],
    activate: (fieldId: string) => activateMutation.mutateAsync(fieldId),
    deactivate: (fieldId: string) => deactivateMutation.mutateAsync(fieldId),
    deprecate: (fieldId: string) => deprecateMutation.mutateAsync(fieldId),
    deleteField: (fieldId: string) => deleteMutation.mutateAsync(fieldId),
  }
}
