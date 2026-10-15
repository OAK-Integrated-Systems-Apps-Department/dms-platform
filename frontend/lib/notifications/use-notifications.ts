"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  archiveNotification,
  fetchDeliveries,
  fetchNotifications,
  fetchPreferences,
  fetchTenantSettings,
  fetchUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  retryDelivery,
  updatePreference,
  updateTenantSetting,
} from "./api"

const INBOX_KEY = ["notifications", "inbox"]
const UNREAD_KEY = ["notifications", "unread"]

/** The bell badge + nav badge: the caller's unread count, polled like My Tasks. */
export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: UNREAD_KEY,
    queryFn: fetchUnreadCount,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    retry: false,
    enabled,
  })
}

export function useNotifications(page: number, size: number, unread = false) {
  return useQuery({
    queryKey: [...INBOX_KEY, page, size, unread],
    queryFn: () => fetchNotifications(page, size, unread),
    refetchInterval: 30_000,
  })
}

function useInboxMutation<T>(fn: (arg: T) => Promise<void>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: INBOX_KEY })
      void qc.invalidateQueries({ queryKey: UNREAD_KEY })
    },
  })
}

export function useMarkRead() {
  return useInboxMutation(markNotificationRead)
}

export function useMarkAllRead() {
  return useInboxMutation(() => markAllNotificationsRead())
}

export function useArchive() {
  return useInboxMutation(archiveNotification)
}

// ---------------------------------------------------------------- preferences

export function usePreferences() {
  return useQuery({ queryKey: ["notifications", "preferences"], queryFn: fetchPreferences })
}

export function useUpdatePreference() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { categoryCode: string; emailEnabled: boolean }) =>
      updatePreference(v.categoryCode, v.emailEnabled),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["notifications", "preferences"] }),
  })
}

// ---------------------------------------------------------------- admin

export function useTenantSettings() {
  return useQuery({ queryKey: ["notifications", "admin", "settings"], queryFn: fetchTenantSettings })
}

export function useUpdateTenantSetting() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { categoryCode: string; emailEnabledDefault: boolean }) =>
      updateTenantSetting(v.categoryCode, v.emailEnabledDefault),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["notifications", "admin", "settings"] }),
  })
}

export function useDeliveries(page: number, size: number, status?: string, channel?: string) {
  return useQuery({
    queryKey: ["notifications", "admin", "deliveries", page, size, status ?? "", channel ?? ""],
    queryFn: () => fetchDeliveries(page, size, status, channel),
  })
}

export function useRetryDelivery() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: retryDelivery,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["notifications", "admin", "deliveries"] }),
  })
}
