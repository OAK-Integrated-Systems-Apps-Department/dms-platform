"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { Download } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { BulkResultsDialog } from "@/components/ownership/bulk-results-dialog"
import { OwnedDocumentsTable } from "@/components/ownership/owned-documents-table"
import { OwnershipHistoryPanel } from "@/components/ownership/ownership-history-panel"
import { TransferDialog, type TransferOutcome, type TransferSubject } from "@/components/ownership/transfer-dialog"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { DocumentPicker } from "@/components/workflow/document-picker"
import { PersonPicker } from "@/components/workflow/person-picker"
import { friendlyMessage, isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { saveDownloadedFile } from "@/lib/download-file"
import { downloadHistoryCsv } from "@/lib/ownership/api"
import { useOwnedDocuments, usePendingDocuments } from "@/lib/ownership/use-ownership"
import { usePageTitle } from "@/lib/use-page-title"
import { lookupPeople } from "@/lib/workflow/api"
import { useDepartmentOptions } from "@/lib/workflow/use-participant-options"

import type { DocumentListItemDto } from "@/lib/documents/api"
import type { BatchSummaryDto, OwnedDocumentsDto, OwnedDocumentItemDto, OwnershipState } from "@/lib/ownership/types"
import type { PersonOptionDto } from "@/lib/workflow/api"

type Tab = "owned" | "pending" | "history"

const PAGE_SIZE = 50

const ALL_DOT_CODES = [
  PermissionCodes.DOT_BULK_SELECT,
  PermissionCodes.DOT_TRANSFER_INITIATE,
  PermissionCodes.DOT_HISTORY_VIEW,
]

/**
 * Document Ownership - review who owns what, move documents one by one or in bulk, work through the documents
 * waiting for a new owner, and look documents' ownership history up. Admins see everything; a department head
 * only the documents they can open, for the people in their department.
 */
export function OwnershipContent() {
  usePageTitle("Document Ownership")
  const { hasPermission } = usePermissions()
  const router = useRouter()
  const search = useSearchParams()

  const canList = hasPermission(PermissionCodes.DOT_BULK_SELECT)
  const canHistory = hasPermission(PermissionCodes.DOT_HISTORY_VIEW)
  const tabs: { value: Tab; label: string }[] = [
    ...(canList
      ? [
          { value: "owned" as const, label: "Owned documents" },
          { value: "pending" as const, label: "Pending reassignment" },
        ]
      : []),
    ...(canHistory ? [{ value: "history" as const, label: "History" }] : []),
  ]
  const requested = search.get("tab")
  const tab = (tabs.find((t) => t.value === requested)?.value ?? tabs[0]?.value ?? "owned") as Tab

  function selectTab(next: Tab) {
    router.replace(`/admin/ownership?tab=${next}`)
  }

  return (
    <DashboardShell initialActiveLabel="Document Ownership">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Document Ownership" }]} />
      <div className="mb-[22px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Document Ownership</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          See who owns which documents and move them when people change roles or leave. The previous owner is always
          kept in the ownership history. To offboard someone, use <strong>Offboard user…</strong> on their user page.
        </p>
      </div>

      <PermissionGate permission={ALL_DOT_CODES}>
        {tabs.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">You can transfer single documents from the document list.</p>
        ) : (
          <>
            <div className="mb-4">
              <SegmentedControl value={tab} onChange={selectTab} options={tabs} />
            </div>
            {tab === "owned" && <OwnedTab />}
            {tab === "pending" && <PendingTab />}
            {tab === "history" && <HistoryTab />}
          </>
        )}
      </PermissionGate>
    </DashboardShell>
  )
}

// ------------------------------------------------------------------ shared list behaviour

function useTransferActions() {
  const { hasPermission } = usePermissions()
  const queryClient = useQueryClient()
  const [subject, setSubject] = useState<TransferSubject | null>(null)
  const [result, setResult] = useState<BatchSummaryDto | null>(null)
  const canBulk = hasPermission(PermissionCodes.DOT_BULK_EXECUTE)
  const canOne = hasPermission(PermissionCodes.DOT_TRANSFER_INITIATE)

  function done(outcome: TransferOutcome) {
    void queryClient.invalidateQueries({ queryKey: ["ownership"] })
    if (outcome.kind === "bulk") setResult(outcome.summary)
    else toast.success("Ownership transferred.")
  }

  const dialogs = (
    <>
      {subject && (
        <TransferDialog
          key={JSON.stringify(subject)}
          subject={subject}
          open
          onOpenChange={(next) => !next && setSubject(null)}
          onDone={done}
        />
      )}
      <BulkResultsDialog summary={result} open={result !== null} onOpenChange={(next) => !next && setResult(null)} />
    </>
  )
  return { canBulk, canOne, setSubject, dialogs }
}

function ListState({ query, children }: { query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown; data?: OwnedDocumentsDto }; children: (data: OwnedDocumentsDto) => React.ReactNode }) {
  if (query.isPending) return <LoadingState message="Loading documents…" />
  if (query.isError || !query.data) {
    return isNetworkError(query.error) ? (
      <BackendUnavailable onRetry={() => void query.refetch()} />
    ) : (
      <LoadFailed error={query.error} fallback="Could not load the documents." />
    )
  }
  return <>{children(query.data)}</>
}

function Pager({ data, page, onPage }: { data: OwnedDocumentsDto; page: number; onPage: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(data.total / data.size))
  if (pages <= 1) return null
  return (
    <div className="mt-3 flex items-center justify-between text-[12.5px] text-muted-foreground">
      <button type="button" disabled={page === 0} onClick={() => onPage(page - 1)} className="font-semibold text-primary disabled:opacity-40">
        Previous
      </button>
      <span>
        Page {page + 1} of {pages}
      </span>
      <button type="button" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} className="font-semibold text-primary disabled:opacity-40">
        Next
      </button>
    </div>
  )
}

function HiddenNote({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <p className="mb-3 rounded-btn border border-border bg-muted px-3.5 py-2.5 text-[12.5px] text-muted-foreground">
      {count.toLocaleString()} more {count === 1 ? "document" : "documents"} can only be transferred by a tenant
      administrator. They are not listed here.
    </p>
  )
}

function useSelection() {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  return {
    selected,
    toggle: (id: string) =>
      setSelected((prev) => {
        const next = new Set(prev)
        if (next.has(id)) next.delete(id)
        else next.add(id)
        return next
      }),
    togglePage: (items: OwnedDocumentItemDto[], select: boolean) =>
      setSelected((prev) => {
        const next = new Set(prev)
        items.forEach((item) => (select ? next.add(item.documentId) : next.delete(item.documentId)))
        return next
      }),
    clear: () => setSelected(new Set()),
  }
}

function DepartmentSelect({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  const departments = useDepartmentOptions()
  return (
    <select
      aria-label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px]"
    >
      <option value="">{label}</option>
      {(departments.data ?? []).map((d) => (
        <option key={d.value} value={d.value}>
          {d.label}
        </option>
      ))}
    </select>
  )
}

// ------------------------------------------------------------------ owned documents

function OwnedTab() {
  const [by, setBy] = useState<"person" | "department">("person")
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [departmentId, setDepartmentId] = useState("")
  const [q, setQ] = useState("")
  const [state, setState] = useState<OwnershipState | "">("")
  const [bin, setBin] = useState<"" | "true" | "false">("")
  const [page, setPage] = useState(0)
  const selection = useSelection()
  const { canBulk, canOne, setSubject, dialogs } = useTransferActions()

  const filters = {
    userId: by === "person" ? person?.userId : undefined,
    departmentId: by === "department" ? departmentId || undefined : undefined,
    q: q.trim() || undefined,
    state,
    recycleBin: bin === "" ? undefined : bin === "true",
  }
  const query = useOwnedDocuments(filters, page, PAGE_SIZE)
  const chosen = Boolean(filters.userId || filters.departmentId)
  const sourceLabel = by === "person" ? (person?.displayName ?? person?.username ?? "this person") : "this department"

  function resetPaging() {
    setPage(0)
    selection.clear()
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <SegmentedControl
          value={by}
          onChange={(next) => {
            setBy(next)
            resetPaging()
          }}
          options={[
            { value: "person", label: "A person" },
            { value: "department", label: "A department" },
          ]}
        />
        <div className="min-w-[260px]">
          {by === "person" ? (
            <PersonPicker
              queryKey={["ownership-owner"]}
              fetchPeople={(s) => lookupPeople(s, 20)}
              value={person}
              onChange={(next) => {
                setPerson(next)
                resetPaging()
              }}
              placeholder="Search for the owner"
            />
          ) : (
            <DepartmentSelect
              value={departmentId}
              onChange={(next) => {
                setDepartmentId(next)
                resetPaging()
              }}
              label="Choose a department"
            />
          )}
        </div>
        <SearchInput
          value={q}
          onChange={(next) => {
            setQ(next)
            resetPaging()
          }}
          placeholder="Search titles"
          className="min-w-[200px]"
          aria-label="Search titles"
        />
        <select
          aria-label="State"
          value={state}
          onChange={(e) => {
            setState(e.target.value as OwnershipState | "")
            resetPaging()
          }}
          className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px]"
        >
          <option value="">Any state</option>
          <option value="assigned">Assigned</option>
          <option value="holding">Holding</option>
        </select>
        <select
          aria-label="Recycle Bin"
          value={bin}
          onChange={(e) => {
            setBin(e.target.value as "" | "true" | "false")
            resetPaging()
          }}
          className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px]"
        >
          <option value="">With Recycle Bin</option>
          <option value="false">Not in Recycle Bin</option>
          <option value="true">Only Recycle Bin</option>
        </select>
      </div>

      {!chosen ? (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-10 text-center text-[13px] text-muted-foreground">
          Choose a person or a department to see the documents they own.
        </div>
      ) : (
        <ListState query={query}>
          {(data) => (
            <>
              <HiddenNote count={data.hiddenCount} />
              <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                <p className="text-[13px] text-muted-foreground">
                  {data.total.toLocaleString()} {data.total === 1 ? "document" : "documents"}
                  {selection.selected.size > 0 && <> · {selection.selected.size.toLocaleString()} selected</>}
                </p>
                {canBulk && (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      disabled={selection.selected.size === 0}
                      onClick={() =>
                        setSubject({
                          kind: "bulk",
                          sourceUserId: filters.userId,
                          sourceDepartmentId: filters.departmentId,
                          sourceLabel,
                          mode: "selected",
                          documentIds: [...selection.selected],
                          total: selection.selected.size,
                        })
                      }
                    >
                      Transfer selected
                    </Button>
                    <Button
                      type="button"
                      disabled={data.visibleCount === 0}
                      onClick={() =>
                        setSubject({
                          kind: "bulk",
                          sourceUserId: filters.userId,
                          sourceDepartmentId: filters.departmentId,
                          sourceLabel,
                          mode: "all",
                          total: data.visibleCount,
                        })
                      }
                    >
                      Transfer all ({data.visibleCount.toLocaleString()})
                    </Button>
                  </div>
                )}
              </div>
              <OwnedDocumentsTable
                items={data.items}
                selected={selection.selected}
                onToggle={selection.toggle}
                onTogglePage={(select) => selection.togglePage(data.items, select)}
                canSelect={canBulk}
                canTransferOne={canOne}
                onTransferOne={(item) => setSubject({ kind: "document", documentId: item.documentId, title: item.title })}
              />
              <Pager data={data} page={page} onPage={setPage} />
            </>
          )}
        </ListState>
      )}
      {dialogs}
    </div>
  )
}

// ------------------------------------------------------------------ pending reassignment

function PendingTab() {
  const [departmentId, setDepartmentId] = useState("")
  const [q, setQ] = useState("")
  const [page, setPage] = useState(0)
  const selection = useSelection()
  const { canBulk, canOne, setSubject, dialogs } = useTransferActions()
  const query = usePendingDocuments({ departmentId: departmentId || undefined, q: q.trim() || undefined }, page, PAGE_SIZE)

  return (
    <div>
      <p className="mb-3 max-w-[720px] text-[13px] text-muted-foreground">
        Documents parked with a holding owner, and documents whose owner has been deactivated or deleted. Choose a new
        owner for them.
      </p>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <DepartmentSelect
          value={departmentId}
          onChange={(next) => {
            setDepartmentId(next)
            setPage(0)
          }}
          label="All departments"
        />
        <SearchInput
          value={q}
          onChange={(next) => {
            setQ(next)
            setPage(0)
          }}
          placeholder="Search titles"
          className="min-w-[200px]"
          aria-label="Search titles"
        />
      </div>
      <ListState query={query}>
        {(data) => (
          <>
            <HiddenNote count={data.hiddenCount} />
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <p className="text-[13px] text-muted-foreground">
                {data.total.toLocaleString()} {data.total === 1 ? "document" : "documents"} waiting
                {selection.selected.size > 0 && <> · {selection.selected.size.toLocaleString()} selected</>}
              </p>
              {canBulk && (
                <Button
                  type="button"
                  disabled={selection.selected.size === 0}
                  onClick={() =>
                    setSubject({
                      kind: "bulk",
                      sourceLabel: "the selected documents",
                      mode: "selected",
                      documentIds: [...selection.selected],
                      total: selection.selected.size,
                    })
                  }
                >
                  Transfer selected
                </Button>
              )}
            </div>
            <OwnedDocumentsTable
              items={data.items}
              selected={selection.selected}
              onToggle={selection.toggle}
              onTogglePage={(select) => selection.togglePage(data.items, select)}
              canSelect={canBulk}
              canTransferOne={canOne}
              onTransferOne={(item) => setSubject({ kind: "document", documentId: item.documentId, title: item.title })}
              showPendingReason
            />
            <Pager data={data} page={page} onPage={setPage} />
          </>
        )}
      </ListState>
      {dialogs}
    </div>
  )
}

// ------------------------------------------------------------------ history

const EXPORT_REASONS = [
  { value: "", label: "Any reason" },
  { value: "manual", label: "Transferred" },
  { value: "offboarding", label: "Offboarding" },
  { value: "reorganization", label: "Reorganisation" },
  { value: "holding", label: "Held for reassignment" },
]

function HistoryTab() {
  const { hasPermission } = usePermissions()
  const canExport = hasPermission(PermissionCodes.DOT_HISTORY_EXPORT)
  const [document, setDocument] = useState<DocumentListItemDto | null>(null)
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [reason, setReason] = useState("")
  const [exporting, setExporting] = useState(false)

  async function exportCsv() {
    setExporting(true)
    try {
      saveDownloadedFile(
        await downloadHistoryCsv({
          from: from ? new Date(`${from}T00:00:00`).toISOString() : undefined,
          to: to ? new Date(`${to}T00:00:00`).toISOString() : undefined,
          documentId: document?.documentId,
          userId: person?.userId,
          reason,
        })
      )
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not export the ownership history."))
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="grid items-start gap-5 lg:grid-cols-2">
      <div className="rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]">
        <h2 className="mb-1 font-heading text-[16.5px] font-semibold">Find a document</h2>
        <p className="mb-3 text-[12.5px] text-muted-foreground">
          Pick a document to see who has owned it. The same pick narrows the export.
        </p>
        {document ? (
          <p className="flex items-center justify-between rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px]">
            <strong>{document.title}</strong>
            <button type="button" className="text-primary hover:underline" onClick={() => setDocument(null)}>
              Change
            </button>
          </p>
        ) : (
          <DocumentPicker pickedIds={[]} onPick={setDocument} />
        )}

        {canExport && (
          <div className="mt-5 flex flex-col gap-3 border-t border-dashed border-border pt-4">
            <h3 className="text-[13px] font-semibold">Export as CSV</h3>
            <div className="grid grid-cols-2 gap-3">
              <label className="flex flex-col gap-1 text-[12.5px] font-semibold">
                From
                <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px] font-normal" />
              </label>
              <label className="flex flex-col gap-1 text-[12.5px] font-semibold">
                To
                <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px] font-normal" />
              </label>
            </div>
            <div className="flex flex-col gap-1 text-[12.5px] font-semibold">
              Person (previous or new owner)
              <PersonPicker queryKey={["ownership-export"]} fetchPeople={(s) => lookupPeople(s, 20)} value={person} onChange={setPerson} />
            </div>
            <select
              aria-label="Reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="h-10 rounded-btn border border-[var(--border-strong)] bg-card px-3 text-[13px]"
            >
              {EXPORT_REASONS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
            <div>
              <Button type="button" onClick={() => void exportCsv()} disabled={exporting}>
                <Download size={14} aria-hidden="true" />
                {exporting ? "Exporting…" : "Export CSV"}
              </Button>
            </div>
          </div>
        )}
      </div>
      {document ? (
        <OwnershipHistoryPanel key={document.documentId} documentId={document.documentId} alwaysVisible />
      ) : (
        <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-10 text-center text-[13px] text-muted-foreground">
          Pick a document to see its ownership history.
        </div>
      )}
    </div>
  )
}
