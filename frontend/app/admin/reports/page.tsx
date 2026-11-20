"use client"

import { useState } from "react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { DeliveryTab } from "@/components/reports/admin/delivery-tab"
import { FieldsTab } from "@/components/reports/admin/fields-tab"
import { GeneralTab } from "@/components/reports/admin/general-tab"
import { LimitsTab } from "@/components/reports/admin/limits-tab"
import { OfficialTemplatesTab } from "@/components/reports/admin/official-templates-tab"
import { OutputTab } from "@/components/reports/admin/output-tab"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

type Tab = "general" | "fields" | "templates" | "delivery" | "output" | "limits"

const TABS: { value: Tab; label: string }[] = [
  { value: "general", label: "General" },
  { value: "fields", label: "Fields" },
  { value: "templates", label: "Official templates" },
  { value: "delivery", label: "Schedules & delivery" },
  { value: "output", label: "Output & branding" },
  { value: "limits", label: "Limits" },
]

/**
 * Reports administration (5.3): whether reporting is on, which fields it offers, official templates, delivery rules, how files look and
 * how big things may get. Each tab saves on its own. The backend enforces the permission again on every call.
 */
export default function ReportsAdminPage() {
  usePageTitle("Reports")
  const { hasPermission } = usePermissions()
  const canConfigure = hasPermission(PermissionCodes.CRG_CONFIGURE_REPORTS)
  const [tab, setTab] = useState<Tab>("general")

  return (
    <DashboardShell initialActiveLabel="Reports" fillHeight>
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Reports" }]} />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Reports</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Decide what people can report on, which reports are official, how scheduled reports are delivered and how the files look.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.CRG_CONFIGURE_REPORTS}>
        <div className="mb-4">
          <SegmentedControl value={tab} onChange={setTab} options={TABS} aria-label="Report administration" />
        </div>
        <div className="flex min-h-0 flex-1 flex-col">
          {tab === "general" && <GeneralTab canEdit={canConfigure} />}
          {tab === "fields" && <FieldsTab canEdit={canConfigure} />}
          {tab === "templates" && <OfficialTemplatesTab />}
          {tab === "delivery" && <DeliveryTab canEdit={canConfigure} />}
          {tab === "output" && <OutputTab canEdit={canConfigure} />}
          {tab === "limits" && <LimitsTab canEdit={canConfigure} />}
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
