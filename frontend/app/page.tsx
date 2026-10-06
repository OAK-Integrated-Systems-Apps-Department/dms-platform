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
import { MetricCards } from "@/components/dashboard/metric-cards"
import { RecentActivityPanel } from "@/components/dashboard/panels"
import { QuickActions } from "@/components/dashboard/quick-actions"
import { RecentDocuments } from "@/components/dashboard/recent-documents"
import { StorageCard } from "@/components/dashboard/storage-card"
import { DocumentPreview } from "@/components/documents/document-preview"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useCurrentUser } from "@/lib/auth/use-current-user"
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
  const { data: user, isLoading: isUserLoading } = useCurrentUser()
  const { hasPermission, isLoading: permissionsLoading } = usePermissions()
  // Assume access until the permissions arrive, so nothing flashes "no access".
  const canView =
    permissionsLoading || hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  const period = useSyncExternalStore<DashboardPeriod>(
    subscribePeriod,
    readPeriod,
    () => 30
  )
  const dashboard = useDashboard(period)
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
    enabled: canView && !permissionsLoading,
    staleTime: 30_000,
  })
  const shared = useQuery({
    queryKey: ["documents", "shared-count"],
    queryFn: () => fetchSharedWithMe({ page: 0, size: 1 }),
    enabled: canView && !permissionsLoading,
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

          <div className="mb-5 grid items-start gap-5 lg:grid-cols-3">
            <div className="flex min-w-0 flex-col gap-5 lg:col-span-2">
              {canView && (
                <RecentDocuments
                  documents={recent.data?.content}
                  owners={owners}
                  loading={recent.isPending && !recent.isError}
                  failed={recent.isError}
                  onRetry={() => void recent.refetch()}
                  onOpen={setPreview}
                  onDownload={(doc) => void download(doc.documentId)}
                  order={11}
                />
              )}
              <ActivityChart data={data} order={13} />
            </div>
            {/* What needs attention comes first on narrow screens. */}
            <div className="order-first flex min-w-0 flex-col gap-5 lg:order-none">
              <AttentionPanel data={data} order={12} />
              <RecentActivityPanel
                activity={data?.activity}
                scope={data?.usageScope}
                currentUserId={user?.userId}
                order={14}
              />
            </div>
          </div>

          {canView && (
            <div className="grid items-stretch gap-5 md:grid-cols-2 2xl:grid-cols-4">
              <StorageCard data={data} order={15} />
              <TypesCard data={data} order={16} />
              <FoldersCard data={data} order={17} />
              <MostOpenedCard data={data} order={18} onOpen={setPreview} />
            </div>
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
