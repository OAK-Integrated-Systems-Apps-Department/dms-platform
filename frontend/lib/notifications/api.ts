import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import type { PageDto } from "@/lib/workflow/api"

const BASE = `${API_BASE_URL}/api/v1`

/** Mirrors backend NotificationView (3.2). */
export interface NotificationDto {
  notificationId: string
  categoryCode: string
  tier: "critical" | "standard" | "optional"
  title: string
  body: string | null
  linkUrl: string | null
  objectType: string | null
  objectId: string | null
  actorUserId: string | null
  sourceModule: string
  read: boolean
  archived: boolean
  createdAt: string
  readAt: string | null
}

/** Mirrors backend PreferenceView. `locked` = critical (always on). */
export interface PreferenceDto {
  categoryCode: string
  module: string
  tier: "critical" | "standard" | "optional"
  displayName: string
  description: string | null
  emailEnabled: boolean
  locked: boolean
}

/** Mirrors backend TenantSettingView (admin). */
export interface TenantSettingDto {
  categoryCode: string
  module: string
  tier: "critical" | "standard" | "optional"
  displayName: string
  description: string | null
  emailEnabledDefault: boolean
  locked: boolean
}

/** Mirrors backend DeliveryLogView (admin). */
export interface DeliveryLogDto {
  deliveryId: string
  notificationId: string
  channel: "email" | "in_app"
  destination: string | null
  status: "pending" | "sending" | "sent" | "failed" | "skipped"
  skipReason: string | null
  attemptCount: number
  maxAttempts: number
  lastError: string | null
  nextAttemptAt: string | null
  sentAt: string | null
  createdAt: string
}

// ---------------------------------------------------------------- inbox (self)

export async function fetchNotifications(
  page: number,
  size: number,
  unread = false,
  category?: string
): Promise<PageDto<NotificationDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size), unread: String(unread) })
  if (category) params.set("category", category)
  return apiFetchJson(`${BASE}/notifications?${params.toString()}`, undefined, "Could not load your notifications.")
}

export async function fetchUnreadCount(): Promise<number> {
  const res = await apiFetchJson<{ count: number }>(
    `${BASE}/notifications/unread-count`,
    undefined,
    "Could not load your notifications."
  )
  return res.count
}

export async function markNotificationRead(id: string): Promise<void> {
  await apiFetch(`${BASE}/notifications/${id}/read`, { method: "POST" }, "Could not update the notification.")
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiFetch(`${BASE}/notifications/read-all`, { method: "POST" }, "Could not update your notifications.")
}

export async function archiveNotification(id: string): Promise<void> {
  await apiFetch(`${BASE}/notifications/${id}/archive`, { method: "POST" }, "Could not archive the notification.")
}

// ---------------------------------------------------------------- preferences (self)

export async function fetchPreferences(): Promise<PreferenceDto[]> {
  return apiFetchJson(`${BASE}/notifications/preferences`, undefined, "Could not load your preferences.")
}

export async function updatePreference(categoryCode: string, emailEnabled: boolean): Promise<void> {
  await apiFetch(
    `${BASE}/notifications/preferences/${categoryCode}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ emailEnabled }) },
    "Could not save your preference."
  )
}

// ---------------------------------------------------------------- admin

export async function fetchTenantSettings(): Promise<TenantSettingDto[]> {
  return apiFetchJson(`${BASE}/admin/notifications/settings`, undefined, "Could not load the settings.")
}

export async function updateTenantSetting(categoryCode: string, emailEnabledDefault: boolean): Promise<void> {
  await apiFetch(
    `${BASE}/admin/notifications/settings/${categoryCode}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ emailEnabledDefault }),
    },
    "Could not save the setting."
  )
}

export async function fetchDeliveries(
  page: number,
  size: number,
  status?: string,
  channel?: string
): Promise<PageDto<DeliveryLogDto>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) params.set("status", status)
  if (channel) params.set("channel", channel)
  return apiFetchJson(`${BASE}/admin/notifications/deliveries?${params.toString()}`, undefined, "Could not load the delivery log.")
}

export async function retryDelivery(deliveryId: string): Promise<void> {
  await apiFetch(`${BASE}/admin/notifications/deliveries/${deliveryId}/retry`, { method: "POST" }, "Could not retry the delivery.")
}
