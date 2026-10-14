"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchMyTasks } from "./api"
import type { MyTasksView } from "./api"

/** How often the queue re-polls - pool tasks can be claimed by someone else at any moment. */
const MY_TASKS_POLL_MS = 15_000

/**
 * The caller's task queue (`GET /api/v1/workflow/tasks/my`). Polls every 15
 * seconds and on window focus so a pool task someone else claimed drops
 * off without a manual refresh. `page` is 1-indexed here.
 */
export function useMyTasks(view: MyTasksView, page: number, size: number) {
  return useQuery({
    queryKey: ["workflow", "tasks", view, page, size],
    queryFn: () => fetchMyTasks(view, page - 1, size),
    placeholderData: keepPreviousData,
    refetchInterval: MY_TASKS_POLL_MS,
    refetchOnWindowFocus: true,
  })
}
