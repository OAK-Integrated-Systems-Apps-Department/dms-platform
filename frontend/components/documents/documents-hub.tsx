"use client"

import type { ReactNode } from "react"
import Link from "next/link"
import { useQuery } from "@tanstack/react-query"
import {
  Download,
  Droplet,
  FileDown,
  FileSpreadsheet,
  FileText,
  FileUp,
  Folder,
  Loader2,
} from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchActiveBulkImport } from "@/lib/bulk-imports/api"
import { fetchDocuments } from "@/lib/documents/api"
import { fetchMyExportJobs, isExportInFlight } from "@/lib/export-jobs/api"
import { useFolders } from "@/lib/folders/use-folders"
import { useWatermarkAvailability } from "@/lib/watermarks/use-watermark-availability"
import { cn } from "@/lib/utils"

export type DocumentsTab =
  | "documents"
  | "bulk-import"
  | "bulk-export"
  | "import-templates"
  | "exports"
  | "watermarks"

interface TabDef {
  key: DocumentsTab
  label: string
  href: string
  icon: typeof FileText
  /** Any one of these lets you open the tab; empty = everyone. */
  permissions: string[]
  description: string
}

const TABS: TabDef[] = [
  {
    key: "documents",
    label: "My Documents",
    href: "/documents",
    icon: FileText,
    permissions: [PermissionCodes.DOCM_DOCUMENTS_VIEW],
    description:
      "Your organisation's documents in shared folders - browse, upload into a folder, move and export.",
  },
  {
    key: "bulk-import",
    label: "Bulk Import",
    href: "/documents/bulk-import",
    icon: FileUp,
    permissions: [PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE],
    description:
      "Import many documents at once - with a template for their metadata, or a quick upload of files only. Every file is virus-scanned as it uploads.",
  },
  {
    key: "bulk-export",
    label: "Bulk Export",
    href: "/documents/bulk-export",
    icon: FileDown,
    permissions: [PermissionCodes.FIE_BULK_EXPORT_EXECUTE],
    description:
      "Export documents with their metadata as a ZIP - by document type or by folder.",
  },
  {
    key: "import-templates",
    label: "Import Templates",
    href: "/documents/import-templates",
    icon: FileSpreadsheet,
    permissions: [
      PermissionCodes.FIE_IMPORT_TEMPLATES_CREATE,
      PermissionCodes.FIE_IMPORT_TEMPLATES_UPDATE,
      PermissionCodes.FIE_IMPORT_TEMPLATES_DELETE,
    ],
    description:
      "Saved Bulk Import templates: the columns people fill in, their names and order, and defaults for the rest.",
  },
  {
    key: "exports",
    label: "My Exports",
    href: "/documents/exports",
    icon: Download,
    permissions: [],
    description:
      "Exports you requested and where each one stands. Finished ones are kept until their expiry time.",
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
 * caller may open, and a live summary - documents, folders, and any import
 * or export still running - so every page in the area reads as one place.
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
      (tab.key !== "watermarks" || watermarks?.present === true) &&
      (tab.permissions.length === 0 || hasAnyPermission(...tab.permissions))
  )
  const current = TABS.find((tab) => tab.key === active) ?? TABS[0]

  return (
    <>
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Documents", href: "/documents" },
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
          canImport={hasPermission(PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE)}
        />
      </div>

      <nav
        aria-label="Documents sections"
        className="mb-6 border-b border-border"
      >
        <ul className="-mb-px flex flex-wrap gap-1">
          {visible.map((tab) => {
            const Icon = tab.icon
            const selected = tab.key === active
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

      {children}
    </>
  )
}

function SummaryStrip({
  canView,
  canImport,
}: {
  canView: boolean
  canImport: boolean
}) {
  const documents = useQuery({
    queryKey: ["documents", "count"],
    queryFn: () =>
      fetchDocuments({ folderId: null, all: true, page: 0, size: 1 }),
    enabled: canView,
    staleTime: 30_000,
  })
  const folders = useFolders(canView)
  const activeImport = useQuery({
    queryKey: ["bulk-imports", "active"],
    queryFn: fetchActiveBulkImport,
    enabled: canImport,
  })
  const exports = useQuery({
    queryKey: ["export-jobs", "mine", "all", 1, 20],
    queryFn: () => fetchMyExportJobs(undefined, 0, 20),
    staleTime: 15_000,
  })
  const exportsRunning = (exports.data?.content ?? []).filter((job) =>
    isExportInFlight(job.status)
  ).length

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
  if (canImport && activeImport.data) {
    items.push({
      icon: <FileUp size={14} />,
      label:
        activeImport.data.status === "uploading"
          ? "Import being uploaded"
          : "Import processing",
      busy: activeImport.data.status !== "uploading",
    })
  }
  if (exportsRunning > 0) {
    items.push({
      icon: <FileDown size={14} />,
      label: `${exportsRunning} ${exportsRunning === 1 ? "export" : "exports"} preparing`,
      busy: true,
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
