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
import { useMetadataFields } from "@/lib/metadata-fields/use-metadata-fields"

import { MetadataFieldsTable } from "@/components/metadata-fields/metadata-fields-table"
import { DATA_TYPES, DATA_TYPE_LABELS, type DataType } from "@/components/metadata-fields/types"

const DEFAULT_PAGE_SIZE = 10

/** Metadata Fields — list (A3). Search/filter by status and data type, add a field, or edit/activate/deactivate/deprecate an existing one. */
export default function MetadataFieldsListPage() {
  usePageTitle("Metadata Fields")

  const { hasPermission, hasAnyPermission } = usePermissions()

  const canView = hasAnyPermission(
    PermissionCodes.MDTM_METADATA_SCHEMA_VIEW_FIELD,
    PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD
  )
  const { fields, isLoading, isError, activate, deactivate, deprecate } = useMetadataFields(canView)
  const canCreate = hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_CREATE_FIELD)
  const canUpdate = hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD)
  const canActivateDeactivate = hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD)
  const canDeprecate = hasPermission(PermissionCodes.MDTM_METADATA_SCHEMA_DEPRECATE_FIELD)

  const [query, setQuery] = useState("")
  const [statusFilter, setStatusFilter] = useState<"all" | "active" | "inactive" | "deprecated">("all")
  const [dataTypeFilter, setDataTypeFilter] = useState<"all" | DataType>("all")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return fields.filter((field) => {
      const matchesQuery = !q || field.label.toLowerCase().includes(q) || field.fieldKey.toLowerCase().includes(q)
      const matchesStatus = statusFilter === "all" || field.status === statusFilter
      const matchesDataType = dataTypeFilter === "all" || field.dataType === dataTypeFilter
      return matchesQuery && matchesStatus && matchesDataType
    })
  }, [fields, query, statusFilter, dataTypeFilter])

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pageFields = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize)

  function handleQueryChange(value: string) {
    setQuery(value)
    setPage(1)
  }

  async function handleToggleStatus(field: (typeof fields)[number]) {
    if (field.status === "active") {
      await deactivate(field.fieldId)
    } else {
      await activate(field.fieldId)
    }
  }

  return (
    <DashboardShell initialActiveLabel="Metadata">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Metadata Fields" }]} />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Metadata Fields</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          The centralized metadata schema registry every document type draws its fields from. Search or filter by
          status and data type, add a field, or edit, activate/deactivate, or deprecate an existing one.
        </p>
      </div>

      <PermissionGate permission={[PermissionCodes.MDTM_METADATA_SCHEMA_VIEW_FIELD, PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD]}>
        <div className="mb-4 flex flex-wrap items-center gap-2.5">
          <SearchInput className="min-w-[220px] flex-1" value={query} onChange={handleQueryChange} placeholder="Search fields…" />

          <div className="relative w-[150px]">
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value as typeof statusFilter); setPage(1) }}
              className="h-[38px] w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
            >
              <option value="all">All statuses</option>
              <option value="active">Active</option>
              <option value="inactive">Inactive</option>
              <option value="deprecated">Deprecated</option>
            </select>
            <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
          </div>

          <div className="relative w-[170px]">
            <select
              value={dataTypeFilter}
              onChange={(event) => { setDataTypeFilter(event.target.value as typeof dataTypeFilter); setPage(1) }}
              className="h-[38px] w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
            >
              <option value="all">All data types</option>
              {DATA_TYPES.map((type) => (
                <option key={type} value={type}>{DATA_TYPE_LABELS[type]}</option>
              ))}
            </select>
            <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
          </div>

          {canCreate && (
            <Link
              href="/admin/metadata/fields/new"
              className="flex items-center gap-1.5 border border-primary bg-primary font-semibold text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] justify-center"
            >
              <Plus size={16} />
              Add Field
            </Link>
          )}
        </div>

        {isLoading ? (
          <LoadingState message="Loading metadata fields…" />
        ) : isError ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]">
            <AlertTriangle size={20} className="text-[var(--rust)]" />
            <p className="text-[13.5px] text-muted-foreground">Could not load metadata fields.</p>
          </div>
        ) : (
          <>
            <MetadataFieldsTable
              fields={pageFields}
              onToggleStatus={handleToggleStatus}
              onDeprecate={(field) => deprecate(field.fieldId).then(() => null).catch((err) =>
                err instanceof Error ? err.message : "Could not deprecate this field."
              )}
              canUpdate={canUpdate}
              canActivateDeactivate={canActivateDeactivate}
              canDeprecate={canDeprecate}
            />
            <TablePagination currentPage={currentPage} totalPages={totalPages} onPageChange={setPage} pageSize={pageSize} onPageSizeChange={(s) => { setPageSize(s); setPage(1) }} />
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}
