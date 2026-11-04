"use client"

import { useState } from "react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RetentionPoliciesTab } from "@/components/retention/retention-policies-tab"
import { RemovedVersionsList } from "@/components/retention/removed-versions-list"
import { RetentionReviewQueue } from "@/components/retention/retention-review-queue"
import { RetentionSettingsCard } from "@/components/retention/retention-settings-card"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

type Tab = "rules" | "settings" | "review" | "without-policy" | "versions"

/**
 * Records retention (6.1): the organisation's retention rules and settings, and the lists an administrator works
 * through - documents past their retention, and documents no rule covers. Each tab shows only what the caller's
 * permissions allow; the backend enforces them all again.
 */
export default function RetentionPage() {
  usePageTitle("Records Retention")
  const { hasAnyPermission, hasPermission } = usePermissions()
  const canSeeRules = hasAnyPermission(
    PermissionCodes.CRM_POLICY_VIEW,
    PermissionCodes.CRM_POLICY_CREATE,
    PermissionCodes.CRM_POLICY_UPDATE,
    PermissionCodes.CRM_POLICY_DELETE,
    PermissionCodes.CRM_CONFIGURE_RETENTION
  )
  const canReview = hasPermission(PermissionCodes.CRM_REVIEW_RETENTION)
  const tabs: { value: Tab; label: string }[] = [
    ...(canSeeRules ? ([{ value: "rules", label: "Rules" }, { value: "settings", label: "Settings" }] as const) : []),
    ...(canReview ? ([{ value: "review", label: "Awaiting review" }, { value: "without-policy", label: "Without a rule" }, { value: "versions", label: "Removed versions" }] as const) : []),
  ]
  const [chosen, setChosen] = useState<Tab | null>(null)
  const tab: Tab = chosen && tabs.some((t) => t.value === chosen) ? chosen : (tabs[0]?.value ?? "rules")

  return (
    <DashboardShell initialActiveLabel="Records Retention" fillHeight>
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Records Retention" }]} />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Records retention</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Decide how long documents stay active and archived, for the whole organisation or per document type, department
          or folder. Documents under legal hold are never archived or changed.
        </p>
      </div>
      <PermissionGate
        permission={[
          PermissionCodes.CRM_POLICY_VIEW,
          PermissionCodes.CRM_POLICY_CREATE,
          PermissionCodes.CRM_POLICY_UPDATE,
          PermissionCodes.CRM_POLICY_DELETE,
          PermissionCodes.CRM_CONFIGURE_RETENTION,
          PermissionCodes.CRM_REVIEW_RETENTION,
        ]}
      >
        <div className="mb-4">
          <SegmentedControl value={tab} onChange={setChosen} options={tabs} />
        </div>
        {/* The tab fills the height that is left, so an empty list is one box to the bottom of the page, not a strip. */}
        <div className="flex min-h-0 flex-1 flex-col [&>div]:flex-1">
          {tab === "rules" && <RetentionPoliciesTab />}
          {tab === "settings" && <RetentionSettingsCard />}
          {tab === "review" && <RetentionReviewQueue kind="review" />}
          {tab === "without-policy" && <RetentionReviewQueue kind="without-policy" />}
          {tab === "versions" && <RemovedVersionsList />}
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
