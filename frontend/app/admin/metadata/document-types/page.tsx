"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { AlertTriangle, ChevronDown, Plus } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { TablePagination } from "@/components/ui/table-pagination"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useDocumentTypes } from "@/lib/document-types/use-document-types"

import { DocumentTypesTable } from "@/components/document-types/document-types-table"

const DEFAULT_PAGE_SIZE = 10

/** Managing templates (V70) opens the list too, to pick the type - read-only for the type itself. */
const TEMPLATE_PERMISSIONS = [
  PermissionCodes.MDTM_DOCUMENT_TEMPLATES_CREATE,
  PermissionCodes.MDTM_DOCUMENT_TEMPLATES_UPDATE,
  PermissionCodes.MDTM_DOCUMENT_TEMPLATES_DELETE,
]

/** Document Types — list (A5). Search/filter, add a type, or view/edit/activate/deactivate an existing one. */
export default function DocumentTypesListPage() {
  usePageTitle("Document Types")

  const { hasPermission, hasAnyPermission } = usePermissions()
  const canView = hasAnyPermission(PermissionCodes.MDTM_CONTENT_TYPES_VIEW, PermissionCodes.MDTM_CONTENT_TYPES_UPDATE, ...TEMPLATE_PERMISSIONS)
  const { documentTypes, isLoading, isError, activate, deactivate } = useDocumentTypes(canView)
  const canCreate = hasPermission(PermissionCodes.MDTM_CONTENT_TYPES_CREATE)
  const canUpdate = hasPermission(PermissionCodes.MDTM_CONTENT_TYPES_UPDATE)
  const canActivateDeactivate = hasPermission(PermissionCodes.MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE)

  const [query, setQuery] = useState("")
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "inactive">("all")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return documentTypes.filter((type) => {
      const matchesQuery = !q || type.typeName.toLowerCase().includes(q) || type.typeCode.toLowerCase().includes(q)
      const matchesStatus = statusFilter === "all" || type.status === statusFilter
      return matchesQuery && matchesStatus
    })
  }, [documentTypes, query, statusFilter])

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pageTypes = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize)

  function handleQueryChange(value: string) {
    setQuery(value)
    setPage(1)
  }

  function handleToggleStatus(type: (typeof documentTypes)[number]) {
    if (type.status === "active") {
      deactivate(type.documentTypeId)
    } else {
      activate(type.documentTypeId)
    }
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Document Types" }]} />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Document Types</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Content types available within the current tenant, each with its own versioned metadata field mapping.
        </p>
      </div>

      <PermissionGate permission={[PermissionCodes.MDTM_CONTENT_TYPES_VIEW, PermissionCodes.MDTM_CONTENT_TYPES_UPDATE, ...TEMPLATE_PERMISSIONS]}>
        <div className="mb-4 flex flex-wrap items-center gap-2.5">
          <SearchInput className="min-w-[220px] flex-1" value={query} onChange={handleQueryChange} placeholder="Search document types…" />

          <div className="relative w-[150px]">
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value as typeof statusFilter); setPage(1) }}
              className="h-[38px] w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
            >
              <option value="all">All statuses</option>
              <option value="active">Active</option>
              <option value="inactive">Inactive</option>
            </select>
            <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
          </div>

          {canCreate && (
            <Link href="/admin/metadata/document-types/new" className="flex items-center gap-1.5 border border-primary bg-primary font-semibold text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] justify-center">
              <Plus size={16} />
              Add Document Type
            </Link>
          )}
        </div>

        {isLoading ? (
          <LoadingState message="Loading document types…" />
        ) : isError ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            <p className="text-[13.5px] text-muted-foreground">Could not load document types.</p>
          </div>
        ) : (
          <>
            <DocumentTypesTable documentTypes={pageTypes} onToggleStatus={handleToggleStatus} canUpdate={canUpdate} canActivateDeactivate={canActivateDeactivate} />
            <TablePagination currentPage={currentPage} totalPages={totalPages} onPageChange={setPage} pageSize={pageSize} onPageSizeChange={(s) => { setPageSize(s); setPage(1) }} />
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}
