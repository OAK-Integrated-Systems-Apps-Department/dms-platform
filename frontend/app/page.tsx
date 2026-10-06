"use client"

import { useState, useSyncExternalStore } from "react"
import { FileLock2, RefreshCw } from "lucide-react"
import { toast } from "sonner"

import "@/components/dashboard/dashboard.css"
import { ActivityChart } from "@/components/dashboard/activity-chart"
import {
  FoldersCard,
  MostOpenedCard,
  SecurityCard,
  TypesCard,
} from "@/components/dashboard/breakdowns"
import { NUMBER } from "@/components/dashboard/charts"
import { DashCard } from "@/components/dashboard/dash-card"
import { DashboardHero } from "@/components/dashboard/dashboard-hero"
import { KpiCards } from "@/components/dashboard/kpi-cards"
import {
  PendingApprovalsPanel,
  RecentActivityPanel,
  RecentDocumentsPanel,
} from "@/components/dashboard/panels"
import { StorageTreemap } from "@/components/dashboard/storage-treemap"
import { DocumentPreview } from "@/components/documents/document-preview"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Button } from "@/components/ui/button"
import { friendlyMessage } from "@/lib/api-error"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import {
  DASHBOARD_PERIODS,
  type DashboardDto,
  type DashboardPeriod,
} from "@/lib/dashboard/api"
import { useDashboard } from "@/lib/dashboard/use-dashboard"
import { downloadDocumentFile } from "@/lib/documents/api"
import { saveDownloadedFile } from "@/lib/download-file"

/** A document the preview can open - from the recent or most-opened lists. */
type PreviewRow = Parameters<typeof DocumentPreview>[0]["row"]

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
 * The home dashboard, from GET /api/v1/dashboard for the chosen period:
 * only what the user can see - documents by the same access rules as My
 * Documents, usage and activity of their own (or everyone's, with the
 * audit-history permission), and upload security with quarantine view.
 */
export default function Page() {
  const { data: user, isLoading: isUserLoading } = useCurrentUser()
  const period = useSyncExternalStore<DashboardPeriod>(
    subscribePeriod,
    readPeriod,
    () => 30
  )
  const dashboard = useDashboard(period)
  const data = dashboard.data
  const showDocuments = !data || data.documentsVisible
  const [preview, setPreview] = useState<PreviewRow | null>(null)

  async function download(documentId: string) {
    try {
      saveDownloadedFile(await downloadDocumentFile(documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  return (
    <DashboardShell initialActiveLabel="Dashboard">
      <div className="dash">
        <DashboardHero
          firstName={user?.displayName.split(" ")[0] ?? ""}
          isLoadingName={isUserLoading}
          summary={data?.documentsVisible ? summaryOf(data) : undefined}
          period={period}
          onPeriodChange={savePeriod}
          refreshing={dashboard.isPlaceholderData}
        />

        {dashboard.isError ? (
          <DashCard className="mb-6 items-center py-10 text-center">
            <p className="mb-3 text-[13.5px] text-[var(--rust)]">
              {friendlyMessage(
                dashboard.error,
                "Could not load the dashboard."
              )}
            </p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void dashboard.refetch()}
            >
              <RefreshCw />
              Try again
            </Button>
          </DashCard>
        ) : showDocuments ? (
          <>
            <KpiCards data={data} />

            <div className="mb-6 grid items-stretch gap-5 lg:grid-cols-[1.7fr_1fr]">
              <ActivityChart data={data} order={5} />
              <StorageTreemap data={data} order={6} />
            </div>

            <div className="mb-6 grid items-stretch gap-5 md:grid-cols-2 xl:grid-cols-3">
              <TypesCard data={data} order={7} />
              <FoldersCard data={data} order={8} />
              <MostOpenedCard data={data} order={9} onOpen={setPreview} />
            </div>

            <div className="mb-6 grid items-stretch gap-5 lg:grid-cols-[1.6fr_1fr]">
              <RecentDocumentsPanel
                documents={data?.recent}
                order={10}
                onOpen={setPreview}
                onDownload={(document) => void download(document.documentId)}
              />
              <RecentActivityPanel
                activity={data?.activity}
                scope={data?.usageScope}
                currentUserId={user?.userId}
                order={11}
              />
            </div>
          </>
        ) : (
          <>
            <DashCard className="mb-6 items-center py-10 text-center">
              <FileLock2 size={28} className="mb-3 text-muted-foreground" />
              <p className="text-[13.5px] text-muted-foreground">
                You don&apos;t have access to documents yet - ask an
                administrator for a role that can view them.
              </p>
            </DashCard>
            <div className="mb-6">
              <RecentActivityPanel
                activity={data?.activity}
                scope={data?.usageScope}
                currentUserId={user?.userId}
                order={2}
              />
            </div>
          </>
        )}

        <div
          className={
            data?.security
              ? "grid items-stretch gap-5 lg:grid-cols-[1.6fr_1fr]"
              : undefined
          }
        >
          {data?.security && <SecurityCard data={data} order={12} />}
          <PendingApprovalsPanel order={13} compact={!data?.security} />
        </div>
      </div>

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
