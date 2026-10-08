"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchWorkflowInstance, fetchWorkflowInstanceActions } from "./api"

/** Timeline entries fetched in one go - a single run rarely has more than a few dozen. */
const TIMELINE_PAGE_SIZE = 200

/**
 * One workflow run's status (stages, tasks, outcome). While it is still in
 * progress it re-polls every 15 seconds, so approvals made by other people
 * show up on an open status page.
 */
export function useWorkflowInstance(instanceId: string | null | undefined) {
  return useQuery({
    queryKey: ["workflow", "instance", instanceId],
    queryFn: () => fetchWorkflowInstance(instanceId!),
    enabled: Boolean(instanceId),
    retry: false,
    refetchInterval: (q) => (q.state.data?.status === "in_progress" ? 15_000 : false),
  })
}

/** The run's timeline, oldest first. */
export function useWorkflowTimeline(instanceId: string | null | undefined) {
  return useQuery({
    queryKey: ["workflow", "instance", instanceId, "actions"],
    queryFn: () => fetchWorkflowInstanceActions(instanceId!, 0, TIMELINE_PAGE_SIZE),
    enabled: Boolean(instanceId),
    retry: false,
  })
}
