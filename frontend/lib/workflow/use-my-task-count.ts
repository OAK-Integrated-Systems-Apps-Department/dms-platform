"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchMyTaskCount } from "./api"

/** Open/overdue counts for the sidebar's My Tasks badge - polled like the queue itself. */
export function useMyTaskCount(enabled = true) {
  return useQuery({
    queryKey: ["workflow", "task-count"],
    queryFn: fetchMyTaskCount,
    refetchInterval: 15_000,
    refetchOnWindowFocus: true,
    retry: false,
    enabled,
  })
}
