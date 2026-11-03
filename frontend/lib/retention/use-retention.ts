"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"

import {
  changeDocumentStatus,
  createRetentionPolicy,
  deleteRetentionPolicy,
  disposeDocument,
  extendRetention,
  fetchDocumentRetention,
  fetchRetentionPolicies,
  fetchRetentionScopes,
  fetchRemovedVersions,
  fetchRetentionSettings,
  fetchReviewQueue,
  fetchWithoutPolicy,
  restoreRemovedVersion,
  runRetentionNow,
  saveRetentionSettings,
  updateRetentionPolicy,
  type RetentionPolicyRequest,
  type RetentionUnit,
} from "./api"

const POLICIES_KEY = ["retention", "policies"] as const
const SETTINGS_KEY = ["retention", "settings"] as const
const REVIEW_KEY = ["retention", "review"] as const
const NO_POLICY_KEY = ["retention", "without-policy"] as const
const documentKey = (documentId: string) => ["retention", "document", documentId] as const

/** A document's status and retention, for its page. */
export function useDocumentRetention(documentId: string | null) {
  return useQuery({
    queryKey: documentKey(documentId ?? ""),
    queryFn: () => fetchDocumentRetention(documentId as string),
    enabled: Boolean(documentId),
    retry: false,
  })
}

/** Everything a status change or a review decision can alter, refreshed together. */
function useRefreshDocument() {
  const queryClient = useQueryClient()
  return (documentId: string) => {
    void queryClient.invalidateQueries({ queryKey: documentKey(documentId) })
    void queryClient.invalidateQueries({ queryKey: ["document", documentId] })
    // Lists: a switched document leaves the normal listings and joins the Archive view (or comes back).
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
    void queryClient.invalidateQueries({ queryKey: REVIEW_KEY })
  }
}

/** Switch a document between Active and Inactive. The server's 409 (legal hold, unchanged) is shown as a toast. */
export function useChangeStatus() {
  const refresh = useRefreshDocument()
  return useMutation({
    mutationFn: ({ documentId, status, reason }: { documentId: string; status: "active" | "inactive"; reason: string }) =>
      changeDocumentStatus(documentId, status, reason),
    onSuccess: (_, { documentId, status }) => {
      refresh(documentId)
      toast.success(status === "inactive" ? "Marked inactive. It now lives in the Archive view." : "Marked active.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not change the status.")),
  })
}

/** The review decisions: keep it longer, or send it to the recycle bin. */
export function useReviewActions() {
  const refresh = useRefreshDocument()
  const queryClient = useQueryClient()
  const extend = useMutation({
    mutationFn: (vars: { documentId: string; amount: number; unit: RetentionUnit; reason: string }) =>
      extendRetention(vars.documentId, vars.amount, vars.unit, vars.reason),
    onSuccess: (_, { documentId }) => {
      refresh(documentId)
      toast.success("Retention extended.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not extend the retention.")),
  })
  const dispose = useMutation({
    mutationFn: (vars: { documentId: string; reason: string }) => disposeDocument(vars.documentId, vars.reason),
    onSuccess: (_, { documentId }) => {
      refresh(documentId)
      void queryClient.invalidateQueries({ queryKey: ["recycle-bin"] })
      toast.success("Moved to the recycle bin.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not dispose of the document.")),
  })
  return { extend, dispose }
}

export function useRetentionPolicies(enabled = true) {
  return useQuery({ queryKey: POLICIES_KEY, queryFn: fetchRetentionPolicies, enabled, retry: false })
}

/** The types, departments and folders a rule can cover - loaded only while the rule form is open. */
export function useRetentionScopes(enabled: boolean) {
  return useQuery({ queryKey: ["retention", "scopes"], queryFn: fetchRetentionScopes, enabled, retry: false, staleTime: 60_000 })
}

export function usePolicyActions() {
  const queryClient = useQueryClient()
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: POLICIES_KEY })
    void queryClient.invalidateQueries({ queryKey: NO_POLICY_KEY })
  }
  const create = useMutation({
    mutationFn: (request: RetentionPolicyRequest) => createRetentionPolicy(request),
    onSuccess: () => {
      refresh()
      toast.success("Rule created. It applies the next time retention runs.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not create the rule.")),
  })
  const update = useMutation({
    mutationFn: ({ policyId, request }: { policyId: string; request: RetentionPolicyRequest }) =>
      updateRetentionPolicy(policyId, request),
    onSuccess: () => {
      refresh()
      toast.success("Rule saved. It applies the next time retention runs.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not save the rule.")),
  })
  const remove = useMutation({
    mutationFn: (policyId: string) => deleteRetentionPolicy(policyId),
    onSuccess: () => {
      refresh()
      toast.success("Rule deleted.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not delete the rule.")),
  })
  return { create, update, remove }
}

export function useRetentionSettings(enabled = true) {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: fetchRetentionSettings, enabled, retry: false })
}

export function useSaveRetentionSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: saveRetentionSettings,
    onSuccess: (saved) => {
      queryClient.setQueryData(SETTINGS_KEY, saved)
      toast.success("Retention settings saved.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not save the retention settings.")),
  })
}

export function useReviewQueue(page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: [...REVIEW_KEY, page, size],
    queryFn: () => fetchReviewQueue(page, size),
    enabled,
    retry: false,
  })
}

export function useWithoutPolicy(page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: [...NO_POLICY_KEY, page, size],
    queryFn: () => fetchWithoutPolicy(page, size),
    enabled,
    retry: false,
  })
}

/** Apply the rules to this organisation now - and refresh everything the run can change. */
export function useRunRetention() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: runRetentionNow,
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ["retention"] })
      void queryClient.invalidateQueries({ queryKey: ["documents"] })
      toast.success(
        `Retention applied: ${result.archived} archived, ${result.flagged} flagged for review, ${result.withoutPolicy} without a rule, ${result.versionsRemoved} old version${result.versionsRemoved === 1 ? "" : "s"} removed.`
      )
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not run the retention rules.")),
  })
}

const REMOVED_VERSIONS_KEY = ["retention", "removed-versions"] as const

/** Old versions removed by retention that can still be restored. */
export function useRemovedVersions(page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: [...REMOVED_VERSIONS_KEY, page, size],
    queryFn: () => fetchRemovedVersions(page, size),
    enabled,
    retry: false,
  })
}

/** Restore a removed version; it is also kept from now on, so the next run does not remove it again. */
export function useRestoreVersion() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (versionId: string) => restoreRemovedVersion(versionId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: REMOVED_VERSIONS_KEY })
      void queryClient.invalidateQueries({ queryKey: ["documents", "versions"] })
      toast.success("Version restored. It will be kept from now on.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not restore the version.")),
  })
}
