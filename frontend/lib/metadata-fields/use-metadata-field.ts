"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  createFieldOption,
  createFieldValidation,
  deleteFieldOption,
  deleteFieldValidation,
  fetchFieldOptions,
  fetchFieldValidations,
  fetchMetadataField,
  updateFieldOption,
  updateFieldValidation,
  updateMetadataField,
  type CreateMetadataFieldOptionPayload,
  type CreateMetadataFieldValidationPayload,
  type UpdateMetadataFieldOptionPayload,
  type UpdateMetadataFieldValidationPayload,
  type UpdateMetadataFieldPayload,
} from "./api"

/**
 * Loads one metadata field plus its options and validation rules — the
 * three things the Edit Field screen (list + inline option editor +
 * validation rule editor) needs together — for the Metadata Fields Edit
 * screen (A4).
 */
export function useMetadataField(fieldId: string) {
  const queryClient = useQueryClient()

  const fieldQuery = useQuery({
    queryKey: ["metadata-field", fieldId],
    queryFn: () => fetchMetadataField(fieldId),
    retry: false,
    enabled: Boolean(fieldId),
  })

  const optionsQuery = useQuery({
    queryKey: ["metadata-field", fieldId, "options"],
    queryFn: () => fetchFieldOptions(fieldId),
    retry: false,
    enabled: Boolean(fieldId),
  })

  const validationsQuery = useQuery({
    queryKey: ["metadata-field", fieldId, "validations"],
    queryFn: () => fetchFieldValidations(fieldId),
    retry: false,
    enabled: Boolean(fieldId),
  })

  const updateFieldMutation = useMutation({
    mutationFn: (payload: UpdateMetadataFieldPayload) => updateMetadataField(fieldId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["metadata-field", fieldId] })
      queryClient.invalidateQueries({ queryKey: ["metadata-fields"] })
    },
  })

  function invalidateOptions() {
    queryClient.invalidateQueries({ queryKey: ["metadata-field", fieldId, "options"] })
  }

  function invalidateValidations() {
    queryClient.invalidateQueries({ queryKey: ["metadata-field", fieldId, "validations"] })
  }

  const addOptionMutation = useMutation({
    mutationFn: (payload: CreateMetadataFieldOptionPayload) => createFieldOption(fieldId, payload),
    onSuccess: invalidateOptions,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not add this option."),
  })

  const updateOptionMutation = useMutation({
    mutationFn: ({ optionId, payload }: { optionId: string; payload: UpdateMetadataFieldOptionPayload }) =>
      updateFieldOption(fieldId, optionId, payload),
    onSuccess: invalidateOptions,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not update this option."),
  })

  const removeOptionMutation = useMutation({
    mutationFn: (optionId: string) => deleteFieldOption(fieldId, optionId),
    onSuccess: invalidateOptions,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not delete this option."),
  })

  const addValidationMutation = useMutation({
    mutationFn: (payload: CreateMetadataFieldValidationPayload) => createFieldValidation(fieldId, payload),
    onSuccess: invalidateValidations,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not add this validation rule."),
  })

  const updateValidationMutation = useMutation({
    mutationFn: ({ validationId, payload }: { validationId: string; payload: UpdateMetadataFieldValidationPayload }) =>
      updateFieldValidation(fieldId, validationId, payload),
    onSuccess: invalidateValidations,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not update this validation rule."),
  })

  const removeValidationMutation = useMutation({
    mutationFn: (validationId: string) => deleteFieldValidation(fieldId, validationId),
    onSuccess: invalidateValidations,
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not delete this validation rule."),
  })

  return {
    field: fieldQuery.data,
    loading: fieldQuery.isLoading,
    notFound: fieldQuery.isError,
    options: optionsQuery.data ?? [],
    optionsLoading: optionsQuery.isLoading,
    validations: validationsQuery.data ?? [],
    validationsLoading: validationsQuery.isLoading,
    updateField: async (payload: UpdateMetadataFieldPayload): Promise<string | null> => {
      try {
        await updateFieldMutation.mutateAsync(payload)
        return null
      } catch (err) {
        return err instanceof Error ? err.message : "Could not save changes."
      }
    },
    addOption: (payload: CreateMetadataFieldOptionPayload) => addOptionMutation.mutate(payload),
    addOptionPending: addOptionMutation.isPending,
    updateOption: (optionId: string, payload: UpdateMetadataFieldOptionPayload) =>
      updateOptionMutation.mutate({ optionId, payload }),
    removeOption: (optionId: string) => removeOptionMutation.mutate(optionId),
    addValidation: (payload: CreateMetadataFieldValidationPayload) => addValidationMutation.mutate(payload),
    updateValidation: (validationId: string, payload: UpdateMetadataFieldValidationPayload) =>
      updateValidationMutation.mutate({ validationId, payload }),
    removeValidation: (validationId: string) => removeValidationMutation.mutate(validationId),
  }
}
