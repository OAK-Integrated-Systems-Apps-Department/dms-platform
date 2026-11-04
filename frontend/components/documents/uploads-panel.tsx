"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FileArchive,
  FolderOpen,
  Loader2,
  RotateCcw,
  ShieldAlert,
  X,
} from "lucide-react"

import { fileKind } from "@/components/documents/browser-items"
import {
  ACTIVE_STATES,
  cancelAllUploads,
  cancelDownload,
  cancelUpload,
  clearUploads,
  FINISHED_OK,
  retryDownload,
  retryUpload,
  RETRYABLE,
  setUploadsCollapsed,
  useUploads,
  type DownloadJob,
  type UploadJob,
} from "@/lib/documents/upload-manager"
import { FOLDERS_KEY } from "@/lib/folders/use-folders"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

function timeLeft(seconds: number | null) {
  if (seconds === null) return "Uploading…"
  if (seconds < 60) return "Less than a minute left"
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} left`
  const hours = Math.round(minutes / 6) / 10
  return `About ${hours} hour${hours === 1 ? "" : "s"} left`
}

function plural(count: number, one: string, many: string) {
  return `${count} ${count === 1 ? one : many}`
}

/**
 * Google Drive style transfers box, bottom right: every file being uploaded
 * with its own progress bar, the overall progress and time left, and what
 * happened to each (uploaded, blocked by the virus scan, failed - with
 * retry); and ZIP downloads of folders or several documents while they're
 * prepared. Uploads keep going while the user moves around the app; closing
 * the tab mid-upload asks first. Rendered once by DashboardShell.
 */
export function UploadsPanel() {
  const { jobs, downloads, collapsed, secondsLeft } = useUploads()
  const [confirmCancel, setConfirmCancel] = useState(false)
  const router = useRouter()
  const queryClient = useQueryClient()

  const active = jobs.filter((j) => ACTIVE_STATES.includes(j.state))
  const succeeded = jobs.filter((j) => FINISHED_OK.includes(j.state)).length
  // Uploaded, but the virus scan runs on - shown as in progress until its verdict is in.
  const checking = jobs.filter((j) => j.state === "pending-scan").length
  const problems = jobs.filter(
    (j) =>
      !ACTIVE_STATES.includes(j.state) &&
      !FINISHED_OK.includes(j.state) &&
      j.state !== "pending-scan"
  ).length
  const busy = active.length > 0
  const preparing = downloads.filter((d) => d.state === "preparing").length
  const downloaded = downloads.filter((d) => d.state === "done").length
  const downloadsFailed = downloads.filter((d) => d.state === "failed").length
  const inProgress = busy || preparing > 0

  // Every finished upload shows up in the lists without a manual refresh.
  const settled = useRef(0)
  useEffect(() => {
    if (succeeded <= settled.current) {
      settled.current = succeeded
      return
    }
    settled.current = succeeded
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
    void queryClient.invalidateQueries({ queryKey: FOLDERS_KEY })
  }, [succeeded, queryClient])

  // Leaving the page would abort what's still uploading.
  useEffect(() => {
    if (!busy) return
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault()
    }
    window.addEventListener("beforeunload", warn)
    return () => window.removeEventListener("beforeunload", warn)
  }, [busy])

  if (jobs.length === 0 && downloads.length === 0) return null

  const totalBytes = active.reduce((sum, j) => sum + j.size, 0)
  const sentBytes = active.reduce((sum, j) => sum + j.loaded, 0)
  const percent =
    totalBytes > 0 ? Math.round((sentBytes / totalBytes) * 100) : 0
  // A scan still running isn't something to cancel (the file is already uploaded), so it
  // doesn't make the panel "in progress" - closing it just stops watching.
  const title = (
    inProgress || checking > 0
      ? [
          busy && `Uploading ${plural(active.length, "item", "items")}`,
          !busy &&
            checking > 0 &&
            `Checking ${plural(checking, "item", "items")} for viruses`,
          preparing > 0 &&
            `Preparing ${plural(preparing, "download", "downloads")}`,
        ]
      : [
          jobs.length > 0 &&
            `${plural(succeeded, "upload", "uploads")} complete${problems > 0 ? `, ${problems} not uploaded` : ""}`,
          downloads.length > 0 &&
            `${plural(downloaded, "download", "downloads")} ready${downloadsFailed > 0 ? `, ${downloadsFailed} failed` : ""}`,
        ]
  )
    .filter(Boolean)
    .join(" · ")

  return (
    <section
      aria-label="Uploads and downloads"
      className="fixed right-4 bottom-4 z-50 w-[380px] max-w-[calc(100vw-32px)] overflow-hidden rounded-xl border border-border bg-card shadow-[0_12px_32px_rgba(0,0,0,0.28)]"
    >
      <header className="flex items-center gap-2 bg-muted/60 py-2.5 pr-2 pl-4">
        <h2 className="min-w-0 flex-1 truncate text-[14px] font-semibold">
          {title}
        </h2>
        <button
          type="button"
          aria-label={collapsed ? "Show uploads" : "Hide uploads"}
          aria-expanded={!collapsed}
          onClick={() => setUploadsCollapsed(!collapsed)}
          className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
        >
          {collapsed ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
        </button>
        <button
          type="button"
          aria-label={inProgress ? "Cancel everything in progress" : "Close"}
          onClick={() => (inProgress ? setConfirmCancel(true) : clearUploads())}
          className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
        >
          <X size={18} />
        </button>
      </header>

      {busy && (
        <div className="border-b border-border">
          <div className="flex items-center justify-between px-4 py-1.5 text-[12px] text-muted-foreground">
            <span>
              {active.every((j) => j.state === "scanning")
                ? "Checking for viruses…"
                : timeLeft(secondsLeft)}
            </span>
            <button
              type="button"
              onClick={() => setConfirmCancel(true)}
              className="font-medium text-primary hover:underline"
            >
              Cancel
            </button>
          </div>
          <div
            role="progressbar"
            aria-label="Overall upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            className="h-1 bg-muted"
          >
            <div
              className="h-full bg-primary transition-[width] duration-300"
              style={{ width: `${percent}%` }}
            />
          </div>
        </div>
      )}

      {confirmCancel && inProgress && (
        <div className="border-b border-border bg-[var(--gold-tint)] px-4 py-3 text-[12.5px]">
          <p className="font-medium text-foreground">
            {busy ? "Cancel all uploads?" : "Cancel the downloads?"}
          </p>
          <p className="mt-0.5 text-muted-foreground">
            {busy
              ? "Files that haven't finished uploading won't be added."
              : "Downloads still being prepared won't be saved."}
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirmCancel(false)}
              className="rounded-md px-2.5 py-1 font-medium text-primary hover:bg-primary/10"
            >
              Continue
            </button>
            <button
              type="button"
              onClick={() => {
                cancelAllUploads()
                setConfirmCancel(false)
              }}
              className="bg-destructive text-white hover:bg-destructive/90 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
            >
              Cancel all
            </button>
          </div>
        </div>
      )}

      {!collapsed && (
        <ul className="max-h-[320px] divide-y divide-border overflow-y-auto">
          {downloads.map((item) => (
            <DownloadRow key={item.id} item={item} />
          ))}
          {jobs.map((job) => (
            <UploadRow
              key={job.id}
              job={job}
              onShowLocation={() =>
                router.push(
                  job.folderId
                    ? `/documents?folder=${encodeURIComponent(job.folderId)}`
                    : "/documents"
                )
              }
            />
          ))}
        </ul>
      )}
    </section>
  )
}

function DownloadRow({ item }: { item: DownloadJob }) {
  const files =
    item.documentCount !== undefined
      ? ` · ${plural(item.documentCount, "file", "files")}`
      : ""
  const detail =
    item.state === "preparing"
      ? `Preparing download…${files}`
      : item.state === "done"
        ? (item.message ?? `Downloaded${files}`)
        : (item.message ?? "Not downloaded")
  return (
    <li className="group flex items-center gap-3 px-4 py-2.5">
      <span className="flex size-8 shrink-0 items-center justify-center rounded bg-[#986f0b]/10 text-[#986f0b]">
        <FileArchive size={16} aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-medium" title={item.name}>
          {item.name}
        </p>
        <p
          className={cn(
            "truncate text-[11.5px]",
            item.state === "failed"
              ? "text-[var(--rust)]"
              : item.state === "done" && item.message
                ? "text-[var(--gold)]"
                : "text-muted-foreground"
          )}
          title={detail}
        >
          {detail}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {(item.state === "failed" || item.state === "cancelled") && (
          <button
            type="button"
            title="Try again"
            aria-label={`Try again: ${item.name}`}
            onClick={() => retryDownload(item.id)}
            className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
          >
            <RotateCcw size={15} />
          </button>
        )}
        {item.state === "preparing" && (
          <>
            <button
              type="button"
              title="Cancel"
              aria-label={`Cancel ${item.name}`}
              onClick={() => cancelDownload(item.id)}
              className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
            >
              <X size={15} />
            </button>
            <Loader2
              size={18}
              className="animate-spin text-primary"
              aria-hidden="true"
            />
          </>
        )}
        {item.state === "done" && (
          <CheckCircle2
            size={18}
            className="text-[#2f9e5f]"
            aria-label="Downloaded"
          />
        )}
        {item.state === "failed" && (
          <AlertCircle
            size={18}
            className="text-[var(--rust)]"
            aria-label="Not downloaded"
          />
        )}
      </div>
    </li>
  )
}

function UploadRow({
  job,
  onShowLocation,
}: {
  job: UploadJob
  onShowLocation: () => void
}) {
  const kind = fileKind(job.name)
  const percent = job.size > 0 ? Math.round((job.loaded / job.size) * 100) : 100
  const ok = FINISHED_OK.includes(job.state)
  const detail =
    job.state === "queued"
      ? `Waiting… · ${job.folderName}`
      : job.state === "uploading"
        ? `${formatBytes(job.loaded)} of ${formatBytes(job.size)} · ${percent}%`
        : job.state === "scanning"
          ? "Checking for viruses…"
          : job.state === "done"
            ? `Uploaded to ${job.folderName}`
            : job.state === "versioned"
              ? "Added as a new version"
              : job.state === "pending-scan"
                ? (job.message ??
                  "Uploaded - checking for viruses… Large files take a few minutes.")
                : (job.message ?? "Not uploaded")

  return (
    <li className="group flex items-center gap-3 px-4 py-2.5">
      <span
        className={cn(
          "flex size-8 shrink-0 items-center justify-center rounded",
          kind.tone
        )}
      >
        <kind.icon size={16} aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-medium" title={job.name}>
          {job.name}
        </p>
        <p
          className={cn(
            "truncate text-[11.5px]",
            ok ||
              ACTIVE_STATES.includes(job.state) ||
              job.state === "pending-scan"
              ? "text-muted-foreground"
              : job.state === "cancelled"
                ? "text-muted-foreground"
                : "text-[var(--rust)]"
          )}
          title={detail}
        >
          {detail}
        </p>
        {(job.state === "uploading" ||
          job.state === "scanning" ||
          job.state === "pending-scan") && (
          <div
            role="progressbar"
            aria-label={`Uploading ${job.name}`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            className="mt-1.5 h-1 overflow-hidden rounded-full bg-muted"
          >
            <div
              className={cn(
                "h-full rounded-full bg-primary transition-[width] duration-300",
                (job.state === "scanning" || job.state === "pending-scan") &&
                  "animate-pulse"
              )}
              style={{ width: `${percent}%` }}
            />
          </div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {ok && (
          <button
            type="button"
            title="Show file location"
            aria-label={`Show where ${job.name} is`}
            onClick={onShowLocation}
            className="hidden size-7 items-center justify-center rounded-full text-muted-foreground group-focus-within:flex group-hover:flex hover:bg-muted hover:text-foreground"
          >
            <FolderOpen size={15} />
          </button>
        )}
        {RETRYABLE.includes(job.state) && (
          <button
            type="button"
            title={job.state === "duplicate" ? "Upload anyway" : "Retry"}
            aria-label={`${job.state === "duplicate" ? "Upload anyway" : "Retry"}: ${job.name}`}
            onClick={() => retryUpload(job.id)}
            className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
          >
            <RotateCcw size={15} />
          </button>
        )}
        {(job.state === "queued" || job.state === "uploading") && (
          <button
            type="button"
            title="Cancel"
            aria-label={`Cancel ${job.name}`}
            onClick={() => cancelUpload(job.id)}
            className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
          >
            <X size={15} />
          </button>
        )}
        <StateIcon job={job} />
      </div>
    </li>
  )
}

function StateIcon({ job }: { job: UploadJob }) {
  switch (job.state) {
    case "queued":
      return null
    case "uploading":
    case "scanning":
      return (
        <Loader2
          size={18}
          className="animate-spin text-primary"
          aria-hidden="true"
        />
      )
    case "done":
    case "versioned":
      return (
        <CheckCircle2
          size={18}
          className="text-[#2f9e5f]"
          aria-label="Uploaded"
        />
      )
    case "pending-scan":
      return (
        <Loader2
          size={18}
          className="animate-spin text-primary"
          aria-label="Being scanned"
        />
      )
    case "blocked":
      return (
        <ShieldAlert
          size={18}
          className="text-[var(--rust)]"
          aria-label="Blocked by the virus scan"
        />
      )
    case "cancelled":
      return null
    default:
      return (
        <AlertCircle
          size={18}
          className="text-[var(--rust)]"
          aria-label="Not uploaded"
        />
      )
  }
}
