"use client"

import { useMemo, useState, useSyncExternalStore } from "react"
import { useQuery } from "@tanstack/react-query"
import { FileLock2 } from "lucide-react"
import { toast } from "sonner"

import "@/components/dashboard/dashboard.css"
import { ActivityChart } from "@/components/dashboard/activity-chart"
import { AttentionPanel } from "@/components/dashboard/attention-panel"
import {
  FoldersCard,
  MostOpenedCard,
  TypesCard,
} from "@/components/dashboard/breakdowns"
import { NUMBER } from "@/components/dashboard/charts"
import {
  DashCard,
  DashStatusProvider,
  EmptyState,
} from "@/components/dashboard/dash-card"
import { DashboardHeader } from "@/components/dashboard/dashboard-header"
import { PlatformDashboard } from "@/components/dashboard/platform-dashboard"
import { DigitalImpact } from "@/components/dashboard/digital-impact"
import { MetricCards } from "@/components/dashboard/metric-cards"
import {
  PendingApprovalsPanel,
  RecentActivityPanel,
} from "@/components/dashboard/panels"
import { QuickActions } from "@/components/dashboard/quick-actions"
import { RecentDocuments } from "@/components/dashboard/recent-documents"
import { StorageCard } from "@/components/dashboard/storage-card"
import { DocumentPreview } from "@/components/documents/document-preview"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useDashboardScope } from "@/lib/auth/use-dashboard-scope"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  DASHBOARD_PERIODS,
  type DashboardDto,
  type DashboardPeriod,
} from "@/lib/dashboard/api"
import { useDashboard } from "@/lib/dashboard/use-dashboard"
import {
  downloadDocumentFile,
  fetchDocuments,
  fetchSharedWithMe,
} from "@/lib/documents/api"
import { saveDownloadedFile } from "@/lib/download-file"
import { usePageTitle } from "@/lib/use-page-title"

/** A document the preview can open - from the recent or most-opened lists. */
type PreviewRow = Parameters<typeof DocumentPreview>[0]["row"]

const RECENT_ROWS = 6

/** The chosen period, remembered in this browser (a convenience - it falls back to 30 days). */
const PERIOD_KEY = "dms.dashboard.days"
const PERIOD_EVENT = "dms-dashboard-days"

function subscribePeriod(onChange: () => void) {
  window.addEventListener("storage", onChange)
  window.addEventListener(PERIOD_EVENT, onChange)
  return () => {
    window.removeEventListener("storage", onChange)
    window.removeEventListener(PERIOD_EVENT, onChange)
  }
}

function readPeriod(): DashboardPeriod {
  try {
    const stored = Number(window.localStorage.getItem(PERIOD_KEY))
    return (DASHBOARD_PERIODS as readonly number[]).includes(stored)
      ? (stored as DashboardPeriod)
      : 30
  } catch {
    return 30
  }
}

function savePeriod(days: DashboardPeriod) {
  try {
    window.localStorage.setItem(PERIOD_KEY, String(days))
  } catch {
    // storage blocked - the choice lasts until the page reloads
  }
  window.dispatchEvent(new Event(PERIOD_EVENT))
}

/** The line under the greeting, from the live figures. */
function summaryOf(data: DashboardDto): string {
  const { total, added } = data.documents
  const today = data.daily[data.daily.length - 1]?.added ?? 0
  const documents = `${NUMBER.format(total)} ${total === 1 ? "document" : "documents"}`
  if (today > 0) return `${documents} · ${today} added today`
  return `${documents} · ${NUMBER.format(added)} added in the last ${data.days} days`
}

/**
 * The home dashboard. Figures come from GET /api/v1/dashboard for the
 * chosen period; the recent-documents table from the document list (GET
 * /api/v1/documents) and the shared count from shared-with-me - all with the
 * caller's own access, so nobody sees what they couldn't open. Each section
 * loads, empties and fails on its own.
 */
export default function Page() {
  usePageTitle("Dashboard")
  const { data: user, isLoading: isUserLoading } = useCurrentUser()
  // A platform administrator belongs to no tenant, so everything below -
  // documents, folders, activity, all of it tenant-scoped - answers 401
  // TENANT_CONTEXT_MISSING for them. They get the cross-tenant view instead.
  //
  // `scopePending` matters as much as the choice itself: until /users/me has
  // answered, neither dashboard is known to be the right one, and rendering
  // the tenant one on the guess that it probably is was what made the wrong
  // dashboard flash up on every reload. See useDashboardScope.
  const {
    isPending: scopePending,
    isPlatform: noTenant,
    isTenant,
  } = useDashboardScope()
  const {
    hasAnyPermission,
    hasPermission,
    isLoading: permissionsLoading,
  } = usePermissions()
  // Assume access until the permissions arrive, so nothing flashes "no access".
  const canView =
    permissionsLoading || hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  // Only people who can actually decide a task get the approvals panel.
  const canApprove = hasAnyPermission(
    PermissionCodes.WA_TASK_APPROVE,
    PermissionCodes.WA_TASK_REJECT
  )
  const period = useSyncExternalStore<DashboardPeriod>(
    subscribePeriod,
    readPeriod,
    () => 30
  )
  // Held until the account is known to have a tenant: this endpoint is
  // tenant-scoped, so firing it while the scope is still pending means a
  // guaranteed 401 for every platform administrator on every page load.
  const dashboard = useDashboard(period, isTenant)
  const data = dashboard.data
  const [preview, setPreview] = useState<PreviewRow | null>(null)

  const recent = useQuery({
    queryKey: ["documents", "dashboard-recent"],
    queryFn: () =>
      fetchDocuments({
        folderId: null,
        all: true,
        page: 0,
        size: RECENT_ROWS,
      }),
    enabled: isTenant && canView && !permissionsLoading,
    staleTime: 30_000,
  })
  const shared = useQuery({
    queryKey: ["documents", "shared-count"],
    queryFn: () => fetchSharedWithMe({ page: 0, size: 1 }),
    enabled: isTenant && canView && !permissionsLoading,
    staleTime: 60_000,
  })

  // Owner names come with the dashboard's own recent list - the same newest documents.
  const owners = useMemo(
    () =>
      new Map(
        (data?.recent ?? [])
          .filter((d) => d.ownerUserId && d.ownerName)
          .map((d) => [d.ownerUserId as string, d.ownerName as string])
      ),
    [data?.recent]
  )

  async function download(documentId: string) {
    try {
      saveDownloadedFile(await downloadDocumentFile(documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  // Neither dashboard yet. The shell and the sidebar are the same either
  // way, so they render immediately and only the content area waits - which
  // is what stops the page rebuilding itself under the reader a moment after
  // it appears.
  if (scopePending) {
    return (
      <DashboardShell initialActiveLabel="Dashboard">
        <LoadingState message="Loading your dashboard…" />
      </DashboardShell>
    )
  }

  if (noTenant) {
    return (
      <DashboardShell initialActiveLabel="Dashboard">
        <div className="mb-5">
          <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Platform overview
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Every organisation on this installation and how much of the system
            each is using. Figures only — no document titles, folders or
            activity.
          </p>
        </div>
        <PlatformDashboard />
      </DashboardShell>
    )
  }

  return (
    <DashboardShell initialActiveLabel="Dashboard">
      <DashStatusProvider
        value={{
          failed: dashboard.isError && !data,
          retry: () => void dashboard.refetch(),
        }}
      >
        <div className="dash">
          <DashboardHeader
            name={user?.displayName ?? ""}
            role={user?.roles[0]}
            isLoadingName={isUserLoading}
            summary={data?.documentsVisible ? summaryOf(data) : undefined}
            period={period}
            onPeriodChange={savePeriod}
            refreshing={dashboard.isPlaceholderData}
          />

          {canView ? (
            <MetricCards
              data={data}
              sharedWithMe={shared.isError ? null : shared.data?.totalElements}
            />
          ) : (
            <DashCard className="mb-5">
              <EmptyState
                icon={FileLock2}
                title="No access to documents yet"
                text="Ask an administrator for a role that can view documents."
              />
            </DashCard>
          )}

          <QuickActions />

          {/* How efficiently - Environmental Impact - beside what needs attention and the latest activity. */}
          <div className="mb-5 grid items-stretch gap-5 lg:grid-cols-3">
            {canView && (
              <div className="flex min-w-0 flex-col lg:col-span-2 [&>section]:flex-1">
                <DigitalImpact order={11} onOpen={setPreview} />
              </div>
            )}
            {/* What needs attention comes first on narrow screens; activity fills the rest of the column. */}
            <div
              className={
                canView
                  ? "order-first flex min-w-0 flex-col gap-5 lg:order-none"
                  : "flex min-w-0 flex-col gap-5 lg:col-span-3"
              }
            >
              <AttentionPanel data={data} order={12} />
              {canApprove && <PendingApprovalsPanel />}
              <div className="flex min-w-0 flex-1 flex-col [&>section]:flex-1">
                <RecentActivityPanel
                  activity={data?.activity}
                  scope={data?.usageScope}
                  currentUserId={user?.userId}
                  order={13}
                />
              </div>
            </div>
          </div>

          {/* Usage over time, full width - room for 30 or 90 days of columns. */}
          <div className="mb-5">
            <ActivityChart data={data} order={14} />
          </div>

          {canView && (
            <>
              <div className="mb-5">
                <RecentDocuments
                  documents={recent.data?.content}
                  owners={owners}
                  loading={recent.isPending && !recent.isError}
                  failed={recent.isError}
                  onRetry={() => void recent.refetch()}
                  onOpen={setPreview}
                  onDownload={(doc) => void download(doc.documentId)}
                  order={15}
                />
              </div>

              <div className="grid items-stretch gap-5 md:grid-cols-2 2xl:grid-cols-4">
                <StorageCard data={data} order={16} />
                <TypesCard data={data} order={17} />
                <FoldersCard data={data} order={18} />
                <MostOpenedCard data={data} order={19} onOpen={setPreview} />
              </div>
            </>
          )}
        </div>
      </DashStatusProvider>

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview.documentId)}
        />
      )}
    </DashboardShell>
  )
}
