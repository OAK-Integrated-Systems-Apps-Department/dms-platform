"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchMyInfoRequests } from "./api"

const POLL_MS = 15_000

/** "Requests for me" - information requests addressed to the caller. Polls like My Tasks. */
export function useMyInfoRequests(status: string, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ["workflow", "info-requests", status, page, size],
    queryFn: () => fetchMyInfoRequests(status, page - 1, size),
    enabled,
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
    placeholderData: keepPreviousData,
  })
}
