"use client"

import type { ReactNode } from "react"
import Link from "next/link"
import { useQuery } from "@tanstack/react-query"
import { Droplet, FileText, Folder, Loader2 } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchDocuments } from "@/lib/documents/api"
import { useFolders } from "@/lib/folders/use-folders"
import { useWatermarkAvailability } from "@/lib/watermarks/use-watermark-availability"
import { cn } from "@/lib/utils"

export type DocumentsTab = "documents" | "watermarks"

interface TabDef {
  key: DocumentsTab
  label: string
  href: string
  icon: typeof FileText
  /** Any one of these lets you open the tab; empty = everyone. */
  permissions: string[]
  description: string
  /** A page inside another tab: not in the tab bar, that tab stays selected. */
  parent?: DocumentsTab
}

const TABS: TabDef[] = [
  {
    key: "documents",
    label: "My Documents",
    href: "/documents",
    icon: FileText,
    permissions: [PermissionCodes.DOCM_DOCUMENTS_VIEW],
    description:
      "Your organisation's documents in shared folders - browse, upload, move and download.",
  },
  {
    key: "watermarks",
    label: "My Watermarked Copies",
    href: "/documents/watermarks",
    icon: Droplet,
    // No permission list, like My Exports: every user may see the copies
    // they asked for themselves. This tab is additionally hidden when the
    // feature is switched off on the deployment - see `visible` below.
    permissions: [],
    description:
      "Watermarked copies you asked for and where each one stands. Finished ones are kept until their expiry time.",
  },
]

/**
 * The shared frame of the Documents area: breadcrumb, title, the tabs the
 * caller may open (the bar shows only once there's more than one), and a
 * live summary of documents and folders.
 */
export function DocumentsHub({
  active,
  children,
}: {
  active: DocumentsTab
  children: ReactNode
}) {
  const { hasPermission, hasAnyPermission } = usePermissions()
  // The watermarks tab also disappears when the feature is switched off on
  // this deployment: the endpoints are not mapped at all, so the page could
  // only ever show an error. The probe is cached for minutes, so this costs
  // nothing per render - see useWatermarkAvailability.
  const { data: watermarks } = useWatermarkAvailability()
  const visible = TABS.filter(
    (tab) =>
      !tab.parent &&
      (tab.key !== "watermarks" || watermarks?.present === true) &&
      (tab.permissions.length === 0 || hasAnyPermission(...tab.permissions))
  )
  const current = TABS.find((tab) => tab.key === active) ?? TABS[0]
  const parent = TABS.find((tab) => tab.key === current.parent)

  return (
    <>
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Documents", href: "/documents" },
          ...(parent ? [{ label: parent.label, href: parent.href }] : []),
          { label: current.label },
        ]}
      />
      <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-[720px]">
          <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            {current.label}
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {current.description}
          </p>
        </div>
        <SummaryStrip
          canView={hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)}
        />
      </div>

      {visible.length > 1 && (
        <nav
          aria-label="Documents sections"
          className="mb-6 border-b border-border"
        >
          <ul className="-mb-px flex flex-wrap gap-1">
            {visible.map((tab) => {
              const Icon = tab.icon
              const selected = tab.key === active || tab.key === current.parent
              return (
                <li key={tab.key}>
                  <Link
                    href={tab.href}
                    aria-current={selected ? "page" : undefined}
                    className={cn(
                      "inline-flex items-center gap-2 border-b-2 px-3.5 py-2.5 text-[13px] font-medium transition-colors",
                      selected
                        ? "border-primary text-foreground"
                        : "border-transparent text-muted-foreground hover:border-border hover:text-foreground"
                    )}
                  >
                    <Icon size={15} aria-hidden="true" />
                    {tab.label}
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>
      )}

      {children}
    </>
  )
}

function SummaryStrip({ canView }: { canView: boolean }) {
  const documents = useQuery({
    queryKey: ["documents", "count"],
    queryFn: () =>
      fetchDocuments({ folderId: null, all: true, page: 0, size: 1 }),
    enabled: canView,
    staleTime: 30_000,
  })
  const folders = useFolders(canView)

  const items: { icon: ReactNode; label: string; busy?: boolean }[] = []
  if (canView) {
    items.push({
      icon: <FileText size={14} />,
      label: documents.data
        ? `${documents.data.totalElements.toLocaleString()} documents`
        : "… documents",
    })
    items.push({
      icon: <Folder size={14} />,
      label: `${folders.flat.length} ${folders.flat.length === 1 ? "folder" : "folders"}`,
    })
  }
  if (items.length === 0) return null

  return (
    <ul className="flex flex-wrap gap-2" aria-label="Summary">
      {items.map((item) => (
        <li
          key={item.label}
          className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1 text-[12.5px] text-muted-foreground shadow-[var(--ledger-shadow)]"
        >
          {item.busy ? (
            <Loader2
              size={13}
              className="animate-spin text-primary"
              aria-hidden="true"
            />
          ) : (
            item.icon
          )}
          {item.label}
        </li>
      ))}
    </ul>
  )
}
