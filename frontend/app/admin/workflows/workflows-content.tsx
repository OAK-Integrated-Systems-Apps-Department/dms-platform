"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { Plus } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { TablePagination } from "@/components/ui/table-pagination"
import { WorkflowTemplatesTable } from "@/components/workflow/workflow-templates-table"
import { isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useDocumentTypeOptions } from "@/lib/workflow/use-participant-options"
import { usePageTitle } from "@/lib/use-page-title"
import { WORKFLOW_TEMPLATE_ANY_PERMISSION } from "@/lib/workflow/permissions"
import { useWorkflowTemplates } from "@/lib/workflow/use-workflow-templates"
import { TEMPLATE_STATUS_LABELS } from "@/lib/workflow/workflow-badges"

import type { TemplateStatus, WorkflowTemplateFilters, WorkflowTemplateSummaryDto } from "@/lib/workflow/api"

const DEFAULT_PAGE_SIZE = 25

const STATUS_OPTIONS = (Object.keys(TEMPLATE_STATUS_LABELS) as TemplateStatus[]).map((status) => ({
  value: status,
  label: TEMPLATE_STATUS_LABELS[status],
}))

/**
 * Workflow Templates (WA, W17a) - every template version, backed by
 * `GET /api/v1/workflow/templates` (server-side filtering/pagination).
 * Rows open the template page, where details, the stage builder and the
 * activate/deactivate/version actions live.
 */
export function WorkflowsContent() {
  usePageTitle("Workflow Templates")

  const router = useRouter()
  const { hasPermission, hasAnyPermission } = usePermissions()
  const canView = hasAnyPermission(...WORKFLOW_TEMPLATE_ANY_PERMISSION)
  const canCreate = hasPermission(PermissionCodes.WA_TEMPLATES_CREATE)

  const [search, setSearch] = useState("")
  const [status, setStatus] = useState<"" | TemplateStatus>("")
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const { data: documentTypeOptions = [] } = useDocumentTypeOptions(canView)

  const filters: WorkflowTemplateFilters = useMemo(
    () => ({ search: search.trim() || undefined, status: status || undefined, documentTypeId: documentTypeId || undefined }),
    [search, status, documentTypeId]
  )

  const { data, isPending, isError, error, refetch } = useWorkflowTemplates(filters, page, pageSize, canView)

  function handleView(row: WorkflowTemplateSummaryDto) {
    router.push(`/admin/workflows/${row.templateId}`)
  }

  const hasActive = Boolean(search || status || documentTypeId)

  return (
    <DashboardShell initialActiveLabel="Workflow Templates">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Workflow Templates" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Workflow Templates</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Approval chains per document type. Each template is versioned: edit a draft, activate it, and create a new
          version to change it later. A document type can have more than one active template - submitters choose
          which one to use when more than one applies.
        </p>
      </div>

      <PermissionGate permission={WORKFLOW_TEMPLATE_ANY_PERMISSION}>
        <div className="mb-4 flex flex-wrap items-center gap-2.5">
          <SearchInput
            value={search}
            onChange={(value) => { setSearch(value); setPage(1) }}
            placeholder="Search name or code…"
            className="w-[260px]"
            aria-label="Search workflow templates"
          />
          <SearchableSelect
            allLabel="All statuses"
            searchable={false}
            selected={status}
            onChange={(value) => { setStatus(value as "" | TemplateStatus); setPage(1) }}
            options={STATUS_OPTIONS}
            className="w-[160px]"
          />
          <SearchableSelect
            allLabel="All document types"
            selected={documentTypeId}
            onChange={(value) => { setDocumentTypeId(value); setPage(1) }}
            options={documentTypeOptions}
            className="w-[210px]"
          />
          {hasActive && (
            <button
              type="button"
              onClick={() => { setSearch(""); setStatus(""); setDocumentTypeId(""); setPage(1) }}
              className="text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
            >
              Clear all
            </button>
          )}
          {canCreate && (
            <Link
              href="/admin/workflows/new"
              className="ml-auto flex items-center gap-1.5 rounded-btn border border-primary bg-primary px-3.5 py-2.5 text-[13px] font-semibold text-primary-foreground"
            >
              <Plus size={16} />
              New Template
            </Link>
          )}
        </div>

        {isPending ? (
          <LoadingState message="Loading workflow templates…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : (
            <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
              {error instanceof Error ? error.message : "Could not load workflow templates."}
            </div>
          )
        ) : (
          <>
            <WorkflowTemplatesTable rows={data?.content ?? []} onView={handleView} />
            <TablePagination
              currentPage={page}
              totalPages={data?.totalPages ?? 1}
              onPageChange={setPage}
              pageSize={pageSize}
              onPageSizeChange={(size) => { setPageSize(size); setPage(1) }}
            />
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}
