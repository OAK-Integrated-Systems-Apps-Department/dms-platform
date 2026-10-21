"use client"

import { useState } from "react"
import { RefreshCw } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { CategoryGroups } from "@/components/notifications/category-groups"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type { TenantSettingDto } from "@/lib/notifications/api"
import {
  useDeliveries,
  useRetryDelivery,
  useTenantSettings,
  useUpdateTenantSetting,
} from "@/lib/notifications/use-notifications"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 20
const STATUSES = ["", "pending", "sending", "sent", "failed", "skipped"]

/** Tenant-admin Notification Settings + delivery log (3.2, N12). */
export default function AdminNotificationsPage() {
  usePageTitle("Notification settings")
  return (
    <DashboardShell initialActiveLabel="Notification Settings">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Notification settings" }]}
      />
      <PermissionGate permission={[PermissionCodes.NOTIF_SETTINGS_MANAGE, PermissionCodes.NOTIF_DELIVERY_LOG_VIEW]}>
        <div className="mb-[22px] max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Notification settings</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Set which categories are emailed by default for everyone in your organisation, and review delivery.
          </p>
        </div>
        <SettingsSection />
        <DeliveryLogSection />
      </PermissionGate>
    </DashboardShell>
  )
}

function SettingsSection() {
  const { data, isPending } = useTenantSettings()
  const update = useUpdateTenantSetting()

  function toggle(s: TenantSettingDto) {
    if (s.locked) return
    update.mutate(
      { categoryCode: s.categoryCode, emailEnabledDefault: !s.emailEnabledDefault },
      { onError: (e) => toast.error(friendlyMessage(e, "Could not save the setting.")) }
    )
  }

  if (isPending) return <LoadingState message="Loading settings…" />

  return (
    <section className="mb-9">
      <h2 className="mb-3 font-heading text-[16px] font-semibold">Category email defaults</h2>
      <CategoryGroups
        items={(data ?? []).map((s) => ({ ...s, enabled: s.emailEnabledDefault }))}
        unit="emailed by default"
        disabled={update.isPending}
        onToggle={(item) => {
          const s = (data ?? []).find((d) => d.categoryCode === item.categoryCode)
          if (s) toggle(s)
        }}
      />
    </section>
  )
}

function DeliveryLogSection() {
  const { hasPermission } = usePermissions()
  const canRetry = hasPermission(PermissionCodes.NOTIF_DELIVERY_LOG_RETRY)
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState("")
  const { data, isPending } = useDeliveries(page - 1, PAGE_SIZE, status || undefined)
  const retry = useRetryDelivery()
  const rows = data?.content ?? []

  return (
    <section>
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="font-heading text-[16px] font-semibold">Delivery log</h2>
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(1)
          }}
          className="rounded-md border border-border bg-card px-2.5 py-1.5 text-[12.5px]"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s === "" ? "All statuses" : s}
            </option>
          ))}
        </select>
      </div>
      {isPending ? (
        <LoadingState message="Loading the delivery log…" />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
            <table className="w-full text-[12.5px]">
              <thead className="border-b border-border text-left text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5 font-semibold">Channel</th>
                  <th className="px-4 py-2.5 font-semibold">Destination</th>
                  <th className="px-4 py-2.5 font-semibold">Status</th>
                  <th className="px-4 py-2.5 font-semibold">Attempts</th>
                  <th className="px-4 py-2.5 font-semibold">When</th>
                  <th className="px-4 py-2.5 font-semibold" />
                </tr>
              </thead>
              <tbody>
                {rows.map((d) => (
                  <tr key={d.deliveryId} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-2.5">{d.channel}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">{d.destination ?? "-"}</td>
                    <td className="px-4 py-2.5">
                      <span
                        className={
                          d.status === "failed"
                            ? "font-semibold text-[var(--rust)]"
                            : d.status === "sent"
                              ? "font-semibold text-[var(--moss,green)]"
                              : "text-foreground"
                        }
                      >
                        {d.status}
                        {d.skipReason ? ` (${d.skipReason})` : ""}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-muted-foreground">
                      {d.attemptCount}/{d.maxAttempts}
                    </td>
                    <td className="px-4 py-2.5 font-mono text-[11.5px] text-muted-foreground">
                      {formatDateTime(d.sentAt ?? d.createdAt)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {canRetry && (d.status === "failed" || d.status === "skipped") && (
                        <button
                          type="button"
                          onClick={() =>
                            retry.mutate(d.deliveryId, {
                              onSuccess: () => toast.success("Delivery re-queued."),
                              onError: (e) => toast.error(friendlyMessage(e, "Could not retry.")),
                            })
                          }
                          className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 font-semibold hover:bg-muted"
                        >
                          <RefreshCw size={12} /> Retry
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                      No deliveries.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <TablePagination
            currentPage={page}
            totalPages={data?.totalPages ?? 1}
            onPageChange={setPage}
            pageSize={PAGE_SIZE}
            onPageSizeChange={() => undefined}
          />
        </>
      )}
    </section>
  )
}
