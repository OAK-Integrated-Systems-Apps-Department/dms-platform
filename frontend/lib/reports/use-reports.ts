"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useEffect } from "react"
import { toast } from "sonner"

import { fetchAllDepartments } from "@/lib/departments/api"
import { saveDownloadedFile } from "@/lib/download-file"
import { fetchUsersPage } from "@/lib/users/api"
import { useRolesStore } from "@/stores/roles-store"

import {
  adminReassignSchedule,
  adminScheduleAction,
  cancelRun,
  cancelSchedule,
  createSchedule,
  createTemplate,
  deleteTemplate,
  downloadRun,
  duplicateTemplate,
  fetchAdminFields,
  fetchAdminSchedules,
  fetchAdminSettings,
  fetchCatalog,
  fetchFieldValues,
  fetchRun,
  fetchRuns,
  fetchScheduleRuns,
  fetchSchedules,
  fetchTemplate,
  fetchTemplates,
  pauseSchedule,
  previewReport,
  publishTemplate,
  resumeSchedule,
  retryRun,
  runScheduleNow,
  saveAdminFields,
  saveAdminSettings,
  shareTemplate,
  startRun,
  unpublishTemplate,
  updateSchedule,
  updateTemplate,
  type FieldOverride,
  type Grant,
  type PublishRequest,
  type RunDto,
  type RunRequest,
  type ScheduleRequest,
  type TemplateRequest,
} from "./api"
import type { ReportDefinition } from "./definition"
import { reportError } from "./messages"

export const reportKeys = {
  catalog: ["reports", "catalog"] as const,
  templates: ["reports", "templates"] as const,
  template: (id: string) => ["reports", "templates", id] as const,
  runs: ["reports", "runs"] as const,
  schedules: ["reports", "schedules"] as const,
  adminSettings: ["reports", "admin", "settings"] as const,
  adminFields: ["reports", "admin", "fields"] as const,
  adminSchedules: ["reports", "admin", "schedules"] as const,
}

/** What the organisation allows and what the caller may do; also the "are reports on" signal for the sidebar. */
export function useReportCatalog(enabled = true) {
  return useQuery({ queryKey: reportKeys.catalog, queryFn: fetchCatalog, enabled, retry: false, staleTime: 60_000 })
}

export function useFieldValues(fieldKey: string | null, search: string, page: number) {
  return useQuery({
    queryKey: ["reports", "field-values", fieldKey, search, page],
    queryFn: () => fetchFieldValues(fieldKey as string, search, page),
    enabled: Boolean(fieldKey),
    staleTime: 30_000,
  })
}

/** The preview for a definition; re-asked when it changes, and only while `enabled` (a valid definition). */
export function useReportPreview(definition: ReportDefinition, enabled: boolean) {
  return useQuery({
    queryKey: ["reports", "preview", definition],
    queryFn: ({ signal }) => previewReport(definition, signal),
    enabled,
    retry: false,
    staleTime: 15_000,
  })
}

// ---------------------------------------------------------------- templates

export function useTemplates() {
  return useQuery({ queryKey: reportKeys.templates, queryFn: fetchTemplates, retry: false })
}

export function useTemplate(id: string | null) {
  return useQuery({
    queryKey: reportKeys.template(id ?? ""),
    queryFn: () => fetchTemplate(id as string),
    enabled: Boolean(id),
    retry: false,
  })
}

function useTemplateMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>, success: string, failure: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      toast.success(success)
      void queryClient.invalidateQueries({ queryKey: reportKeys.templates })
    },
    onError: (error) => toast.error(reportError(error, failure)),
  })
}

export const useCreateTemplate = () =>
  useTemplateMutation((r: TemplateRequest) => createTemplate(r), "Report saved.", "Could not save the report.")
export const useUpdateTemplate = () =>
  useTemplateMutation((v: { id: string; request: TemplateRequest }) => updateTemplate(v.id, v.request), "Report saved.", "Could not save the report.")
export const useDeleteTemplate = () =>
  useTemplateMutation((id: string) => deleteTemplate(id), "Report deleted.", "Could not delete the report.")
export const useDuplicateTemplate = () =>
  useTemplateMutation((id: string) => duplicateTemplate(id), "A copy was added to your reports.", "Could not duplicate the report.")
export const useShareTemplate = () =>
  useTemplateMutation((v: { id: string; grants: Grant[] }) => shareTemplate(v.id, v.grants), "Sharing updated.", "Could not share the report.")
export const usePublishTemplate = () =>
  useTemplateMutation((v: { id: string; request: PublishRequest }) => publishTemplate(v.id, v.request), "Report published.", "Could not publish the report.")
export const useUnpublishTemplate = () =>
  useTemplateMutation((id: string) => unpublishTemplate(id), "Report unpublished.", "Could not unpublish the report.")

// ---------------------------------------------------------------- runs

/** Runs finished for good: polling stops. */
export const isFinished = (run: RunDto | undefined) =>
  !run || run.status === "completed" || run.status === "failed" || run.status === "cancelled" || run.status === "expired"

/** Run history. While any run is open the list refreshes, backing off from 2 s to 15 s (never faster than the user can read). */
export function useRuns(params: { status?: string; page?: number }) {
  return useQuery({
    queryKey: [...reportKeys.runs, params],
    queryFn: () => fetchRuns({ ...params, size: 20 }),
    retry: false,
    refetchInterval: (query) => {
      const open = query.state.data?.content.some((r) => !isFinished(r))
      if (!open) return false
      const failures = query.state.dataUpdateCount
      return Math.min(15_000, 2_000 * 1.5 ** Math.min(failures, 8))
    },
  })
}

/** One run, polled with back-off until it finishes. */
export function useRun(runId: string | null) {
  return useQuery({
    queryKey: [...reportKeys.runs, "one", runId],
    queryFn: () => fetchRun(runId as string),
    enabled: Boolean(runId),
    retry: false,
    refetchInterval: (query) => (isFinished(query.state.data) && query.state.data ? false : Math.min(15_000, 1_500 * 1.5 ** Math.min(query.state.dataUpdateCount, 8))),
  })
}

export function useStartRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: RunRequest) => startRun(request),
    onSuccess: () => {
      toast.success("Your report is being prepared. We will tell you when it is ready.")
      void queryClient.invalidateQueries({ queryKey: reportKeys.runs })
    },
    onError: (error) => toast.error(reportError(error, "Could not start the report.")),
  })
}

export function useCancelRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: cancelRun,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: reportKeys.runs }),
    onError: (error) => toast.error(reportError(error, "Could not cancel the report.")),
  })
}

export function useRetryRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: retryRun,
    onSuccess: () => {
      toast.success("Trying again.")
      void queryClient.invalidateQueries({ queryKey: reportKeys.runs })
    },
    onError: (error) => toast.error(reportError(error, "Could not retry the report.")),
  })
}

export function useDownloadRun() {
  return useMutation({
    mutationFn: async (runId: string) => saveDownloadedFile(await downloadRun(runId)),
    onError: (error) => toast.error(reportError(error, "Could not download the report.")),
  })
}

// ---------------------------------------------------------------- schedules

export function useSchedules() {
  return useQuery({ queryKey: reportKeys.schedules, queryFn: fetchSchedules, retry: false })
}

export function useScheduleRuns(id: string | null) {
  return useQuery({ queryKey: [...reportKeys.schedules, id, "runs"], queryFn: () => fetchScheduleRuns(id as string), enabled: Boolean(id), retry: false })
}

function useScheduleMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>, success: string, failure: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      toast.success(success)
      void queryClient.invalidateQueries({ queryKey: reportKeys.schedules })
      void queryClient.invalidateQueries({ queryKey: reportKeys.templates })
      void queryClient.invalidateQueries({ queryKey: reportKeys.runs })
    },
    onError: (error) => toast.error(reportError(error, failure)),
  })
}

export const useCreateSchedule = () =>
  useScheduleMutation((r: ScheduleRequest) => createSchedule(r), "Schedule saved.", "Could not save the schedule.")
export const useUpdateSchedule = () =>
  useScheduleMutation((v: { id: string; request: ScheduleRequest }) => updateSchedule(v.id, v.request), "Schedule saved.", "Could not save the schedule.")
export const usePauseSchedule = () => useScheduleMutation(pauseSchedule, "Schedule paused.", "Could not pause the schedule.")
export const useResumeSchedule = () => useScheduleMutation(resumeSchedule, "Schedule resumed.", "Could not resume the schedule.")
export const useCancelSchedule = () => useScheduleMutation(cancelSchedule, "Schedule cancelled.", "Could not cancel the schedule.")
export const useRunScheduleNow = () => useScheduleMutation(runScheduleNow, "Running now. Everyone it is for will get their file.", "Could not run the schedule.")

// ---------------------------------------------------------------- administration

export function useAdminSettings() {
  return useQuery({ queryKey: reportKeys.adminSettings, queryFn: fetchAdminSettings, retry: false })
}

/** Saves settings; the caller shows validation problems inline (the error is rethrown to the caller via mutateAsync). */
export function useSaveAdminSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (update: Record<string, unknown>) => saveAdminSettings(update),
    onSuccess: (saved) => {
      queryClient.setQueryData(reportKeys.adminSettings, saved)
      void queryClient.invalidateQueries({ queryKey: reportKeys.catalog })
      toast.success("Settings saved.")
    },
  })
}

export function useAdminFields() {
  return useQuery({ queryKey: reportKeys.adminFields, queryFn: fetchAdminFields, retry: false })
}

export function useSaveAdminFields() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (overrides: FieldOverride[]) => saveAdminFields(overrides),
    onSuccess: (saved) => {
      queryClient.setQueryData(reportKeys.adminFields, saved)
      void queryClient.invalidateQueries({ queryKey: reportKeys.catalog })
      toast.success("Fields saved.")
    },
  })
}

export function useAdminSchedules(params: { status?: string; page?: number }) {
  return useQuery({ queryKey: [...reportKeys.adminSchedules, params], queryFn: () => fetchAdminSchedules({ ...params, size: 20 }), retry: false })
}

export function useAdminScheduleAction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: string; action: "pause" | "resume" | "cancel" }) => adminScheduleAction(v.id, v.action),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: reportKeys.adminSchedules }),
    onError: (error) => toast.error(reportError(error, "Could not change the schedule.")),
  })
}

export function useAdminReassign() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: string; newOwnerUserId: string }) => adminReassignSchedule(v.id, v.newOwnerUserId),
    onSuccess: () => {
      toast.success("Schedule handed over.")
      void queryClient.invalidateQueries({ queryKey: reportKeys.adminSchedules })
    },
    onError: (error) => toast.error(reportError(error, "Could not hand over the schedule.")),
  })
}

// ---------------------------------------------------------------- people, roles and departments (pickers)

export interface DirectoryOption {
  value: string
  label: string
}

export interface RecipientDirectory {
  users: DirectoryOption[]
  roles: DirectoryOption[]
  departments: DirectoryOption[]
  loading: boolean
}

/** Who a report can be shared with or sent to: active users, roles and active departments of the organisation. */
export function useRecipientDirectory(): RecipientDirectory {
  const users = useQuery({
    queryKey: ["reports", "directory", "users"],
    queryFn: () => fetchUsersPage(0, 200),
    staleTime: 60_000,
    retry: false,
  })
  const departments = useQuery({
    queryKey: ["reports", "directory", "departments"],
    queryFn: () => fetchAllDepartments("active"),
    staleTime: 60_000,
    retry: false,
  })
  const roles = useRolesStore((s) => s.roles)
  const fetchRoles = useRolesStore((s) => s.fetchRoles)
  useEffect(() => {
    if (roles.length === 0) void fetchRoles()
  }, [roles.length, fetchRoles])
  return {
    users: (users.data?.content ?? []).filter((u) => u.status === "active").map((u) => ({ value: u.userId, label: u.displayName })),
    roles: roles.filter((r) => r.status === "Active").map((r) => ({ value: r.id, label: r.name })),
    departments: (departments.data ?? []).map((d) => ({ value: d.departmentId, label: d.name })),
    loading: users.isPending || departments.isPending,
  }
}
