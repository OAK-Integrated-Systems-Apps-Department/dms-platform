"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  activateWorkflowTemplate,
  addWorkflowStage,
  createWorkflowTemplateVersion,
  deactivateWorkflowTemplate,
  deleteWorkflowStage,
  deleteWorkflowTemplate,
  fetchWorkflowTemplate,
  fetchWorkflowTemplateVersions,
  reorderWorkflowStages,
  updateWorkflowStage,
  updateWorkflowTemplate,
} from "./api"
import type { UpsertStagePayload, UpdateWorkflowTemplatePayload, WorkflowTemplateDto } from "./api"
import { workflowErrorMessage } from "./workflow-errors"

/**
 * One template version plus every edit the template page makes to it, in
 * the `lib/quarantine` hook style. Every template/stage endpoint returns the
 * full updated template, so a success writes it straight into the cache
 * (no refetch round-trip) and invalidates the list/versions.
 *
 * Each action resolves to the updated template, or `null` when it failed
 * (the error is already toasted) - callers use that to leave edit mode or
 * navigate only on success.
 */
export function useWorkflowTemplate(templateId: string) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ["workflow", "template", templateId],
    queryFn: () => fetchWorkflowTemplate(templateId),
    enabled: Boolean(templateId),
    retry: false,
  })

  function applyTemplate(template: WorkflowTemplateDto) {
    queryClient.setQueryData(["workflow", "template", template.templateId], template)
    queryClient.invalidateQueries({ queryKey: ["workflow", "templates"] })
    queryClient.invalidateQueries({ queryKey: ["workflow", "template-versions"] })
  }

  const mutation = useMutation({
    mutationFn: async ({ run }: { run: () => Promise<WorkflowTemplateDto>; success: string; failure: string }) => run(),
    onSuccess: (template, { success }) => {
      applyTemplate(template)
      toast.success(success)
    },
    onError: (error, { failure }) => toast.error(workflowErrorMessage(error, failure)),
  })

  async function run(fn: () => Promise<WorkflowTemplateDto>, success: string, failure: string) {
    try {
      return await mutation.mutateAsync({ run: fn, success, failure })
    } catch {
      return null
    }
  }

  const deleteMutation = useMutation({
    mutationFn: () => deleteWorkflowTemplate(templateId),
    // The deleted template's own cache entry is left alone: the page
    // navigates away on success, and removing it first would make the
    // still-mounted page refetch it and hit a 404.
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["workflow", "templates"] })
      queryClient.invalidateQueries({ queryKey: ["workflow", "template-versions"] })
      toast.success("Template version deleted.")
    },
    onError: (error) => toast.error(workflowErrorMessage(error, "Could not delete this template.")),
  })

  return {
    ...query,
    template: query.data,
    isSaving: mutation.isPending || deleteMutation.isPending,
    updateDetails: (payload: UpdateWorkflowTemplatePayload) =>
      run(() => updateWorkflowTemplate(templateId, payload), "Template details saved.", "Could not save changes."),
    createVersion: () =>
      run(() => createWorkflowTemplateVersion(templateId), "New draft version created.", "Could not create a new version."),
    activate: () => run(() => activateWorkflowTemplate(templateId), "Template activated.", "Could not activate this template."),
    deactivate: () =>
      run(() => deactivateWorkflowTemplate(templateId), "Template deactivated.", "Could not deactivate this template."),
    addStage: (payload: UpsertStagePayload) =>
      run(() => addWorkflowStage(templateId, payload), "Stage added.", "Could not add this stage."),
    updateStage: (stageId: string, payload: UpsertStagePayload) =>
      run(() => updateWorkflowStage(templateId, stageId, payload), "Stage saved.", "Could not save this stage."),
    deleteStage: (stageId: string) =>
      run(() => deleteWorkflowStage(templateId, stageId), "Stage deleted.", "Could not delete this stage."),
    reorderStages: (stageIds: string[]) =>
      run(() => reorderWorkflowStages(templateId, stageIds), "Stage order saved.", "Could not reorder the stages."),
    remove: async (): Promise<boolean> => {
      try {
        await deleteMutation.mutateAsync()
        return true
      } catch {
        return false
      }
    },
  }
}

/** Every version in this template's family, for the versions list on the template page. */
export function useWorkflowTemplateVersions(templateId: string) {
  return useQuery({
    queryKey: ["workflow", "template-versions", templateId],
    queryFn: () => fetchWorkflowTemplateVersions(templateId),
    enabled: Boolean(templateId),
  })
}
