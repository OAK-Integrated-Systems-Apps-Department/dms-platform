"use client"

import { create } from "zustand"

import { ApiError, friendlyMessage } from "@/lib/api-error"
import { refreshSession } from "@/lib/api-fetch"
import { beginSessionKeepAlive } from "@/lib/auth/use-proactive-session-refresh"
import {
  fetchIngestionTicket,
  TOO_LARGE_MESSAGE,
  uploadDocument,
  uploadDocumentVersion,
  type IngestionTicketDto,
} from "@/lib/documents/api"
import {
  exportDownloadUrl,
  fetchExportJob,
  requestExport,
  type CreateExportRequestDto,
} from "@/lib/export-jobs/api"

/** Files sent at the same time - enough to be quick, few enough not to flood the scan gate. */
const CONCURRENCY = 3

/** What one upload sends. */
export type UploadRequest =
  | {
      kind: "new"
      file: File
      documentTypeId: string
      title: string
      folderId: string | null
      /** Checked for duplicates and found none: refuse if the same file arrived meanwhile. */
      refuseDuplicates: boolean
    }
  | { kind: "version"; file: File; documentId: string; comment: string }

export type UploadJobState =
  | "queued"
  | "uploading"
  /** Every byte sent - the server is virus scanning it. */
  | "scanning"
  | "done"
  | "versioned"
  /**
   * Every byte sent and stored, but the virus scan runs on after the upload
   * (a cloud scanner, or a file too large to scan within the request). Its
   * ticket is polled until the verdict is in.
   */
  | "pending-scan"
  | "blocked"
  | "rejected"
  | "failed"
  /** Someone added the same file while this one waited. */
  | "duplicate"
  | "cancelled"

export interface UploadJob {
  id: string
  name: string
  size: number
  /** Where it goes - shown under the name, and opened by "Show file location". */
  folderId: string | null
  folderName: string
  state: UploadJobState
  /** Bytes of the file sent so far. */
  loaded: number
  message?: string
  /** The document it became, once uploaded. */
  documentId?: string
  request: UploadRequest
}

export const ACTIVE_STATES: UploadJobState[] = [
  "queued",
  "uploading",
  "scanning",
]
export const FINISHED_OK: UploadJobState[] = ["done", "versioned"]
export const RETRYABLE: UploadJobState[] = ["failed", "duplicate", "cancelled"]

/**
 * A ZIP download (a folder, or several documents), Google Drive style: the
 * server prepares it in the background, then the browser downloads it.
 */
export interface DownloadJob {
  id: string
  /** "Finance.zip" */
  name: string
  state: "preparing" | "done" | "failed" | "cancelled"
  message?: string
  documentCount?: number
  request: CreateExportRequestDto
}

interface UploadsState {
  jobs: UploadJob[]
  downloads: DownloadJob[]
  collapsed: boolean
  /** Seconds left for everything still uploading, once there's enough to estimate from. */
  secondsLeft: number | null
}

export const useUploads = create<UploadsState>(() => ({
  jobs: [],
  downloads: [],
  collapsed: false,
  secondsLeft: null,
}))

const controllers = new Map<string, AbortController>()
const batches = new Map<
  string,
  { remaining: Set<string>; onDone?: () => void }
>()
let nextJobId = 0
let endKeepAlive: (() => void) | null = null
/** Since when bytes have been flowing, for the time-left estimate. */
let session: { startedAt: number; loadedAtStart: number } | null = null

function patch(id: string, change: Partial<UploadJob>) {
  useUploads.setState((s) => ({
    jobs: s.jobs.map((job) => (job.id === id ? { ...job, ...change } : job)),
  }))
}

function job(id: string) {
  return useUploads.getState().jobs.find((j) => j.id === id)
}

/**
 * Hands files to the uploads panel. They start at once, CONCURRENCY at a
 * time, and keep going while the user browses elsewhere in the app.
 * `onDone` runs once every file of this batch has finished, however it went.
 */
export function enqueueUploads(
  items: {
    request: UploadRequest
    folderId: string | null
    folderName: string
  }[],
  onDone?: () => void
) {
  if (items.length === 0) return
  const jobs: UploadJob[] = items.map(({ request, folderId, folderName }) => ({
    id: `job-${++nextJobId}`,
    name: request.file.name,
    size: request.file.size,
    folderId,
    folderName,
    state: "queued",
    loaded: 0,
    request,
  }))
  batches.set(`batch-${nextJobId}`, {
    remaining: new Set(jobs.map((j) => j.id)),
    onDone,
  })
  useUploads.setState((s) => ({ jobs: [...s.jobs, ...jobs], collapsed: false }))
  pump()
}

/** Starts waiting jobs while there's room; keeps the session alive while any are running. */
function pump() {
  const { jobs } = useUploads.getState()
  let running = jobs.filter(
    (j) => j.state === "uploading" || j.state === "scanning"
  ).length
  for (const next of jobs) {
    if (running >= CONCURRENCY) break
    if (next.state !== "queued") continue
    running++
    void run(next.id)
  }
  // A scan still being waited on keeps the session alive too, so its polling isn't logged out.
  const active = useUploads
    .getState()
    .jobs.some(
      (j) => ACTIVE_STATES.includes(j.state) || j.state === "pending-scan"
    )
  if (active && !endKeepAlive) {
    endKeepAlive = beginSessionKeepAlive()
    session = { startedAt: Date.now(), loadedAtStart: totalLoaded() }
  } else if (!active && endKeepAlive) {
    endKeepAlive()
    endKeepAlive = null
    session = null
    useUploads.setState({ secondsLeft: null })
  }
}

function totalLoaded() {
  return useUploads
    .getState()
    .jobs.filter((j) => ACTIVE_STATES.includes(j.state))
    .reduce((sum, j) => sum + j.loaded, 0)
}

/** Time left from the average speed since uploading started - once there are a few seconds to go on. */
function estimate() {
  if (!session) return
  const elapsed = (Date.now() - session.startedAt) / 1000
  const sent = totalLoaded() - session.loadedAtStart
  if (elapsed < 3 || sent <= 0) return
  const left = useUploads
    .getState()
    .jobs.filter((j) => ACTIVE_STATES.includes(j.state))
    .reduce((sum, j) => sum + (j.size - j.loaded), 0)
  useUploads.setState({ secondsLeft: Math.round(left / (sent / elapsed)) })
}

async function run(id: string) {
  const current = job(id)
  if (!current) return
  const controller = new AbortController()
  controllers.set(id, controller)
  patch(id, { state: "uploading", loaded: 0, message: undefined })
  let lastShown = 0
  const transfer = {
    signal: controller.signal,
    onProgress(loaded: number, total: number) {
      // The body is the file plus a little multipart framing - scale to the file.
      const sent = Math.min(
        current.size,
        Math.round((loaded / total) * current.size)
      )
      const done = loaded >= total
      // Repaint at most every 1% (or on completion), not on every network packet.
      if (!done && sent - lastShown < current.size / 100) return
      lastShown = sent
      patch(id, { loaded: sent, state: done ? "scanning" : "uploading" })
      estimate()
    },
  }
  const request = current.request
  try {
    const ticket: IngestionTicketDto =
      request.kind === "version"
        ? await uploadDocumentVersion(
            request.documentId,
            request.file,
            request.comment,
            transfer
          )
        : await uploadDocument(
            request.file,
            request.documentTypeId,
            request.title,
            request.folderId,
            request.refuseDuplicates,
            transfer
          )
    patch(id, { loaded: current.size, ...outcomeOfTicket(ticket, request) })
    if (ticket.status === "pending")
      void watchScan(id, ticket.scanResultId, onDoneOf(id))
  } catch (error) {
    patch(id, outcomeOf(error))
  } finally {
    controllers.delete(id)
    finish(id)
    pump()
  }
}

/** What the upload panel shows for a ticket - the scan's verdict, or still waiting for it. */
function outcomeOfTicket(
  ticket: IngestionTicketDto,
  request: UploadRequest
): Partial<UploadJob> {
  switch (ticket.status) {
    case "pending":
      return {
        state: "pending-scan",
        // Only worth saying when it's held up (the scanner is unavailable and it will retry).
        message: ticket.retrying ? (ticket.message ?? undefined) : undefined,
      }
    case "clean":
    case "released_override":
      return {
        state: request.kind === "version" ? "versioned" : "done",
        documentId: ticket.documentId ?? undefined,
        message: undefined,
      }
    case "infected":
    case "quarantined":
      return {
        state: "blocked",
        message: ticket.message ?? "Blocked by the virus scan.",
      }
    case "unscannable":
      return {
        state: "rejected",
        message: ticket.message ?? "This file couldn't be checked for viruses.",
      }
    default:
      return {
        state: "failed",
        message: ticket.message ?? "The virus scan didn't finish.",
      }
  }
}

/** How often a scan that runs on after its upload is checked on. */
const SCAN_POLL_MS = 4000
/** Checks in a row that must fail before the panel says the server can't be reached (~30 s). */
const SCAN_POLL_FAILURES_SHOWN = 8

/**
 * Follows a scan that finishes after its upload until the verdict is in, so
 * the panel ends on Uploaded / Blocked / Not uploaded rather than leaving the
 * file in limbo. `onScanned` (the batch's callback) runs again once it's
 * clean, so whatever lists the file shows it. Stops if the panel is cleared.
 * A failed check (network, server restart) is simply tried again.
 */
async function watchScan(
  id: string,
  scanResultId: string,
  onScanned?: () => void
) {
  let failures = 0
  while (job(id)?.state === "pending-scan") {
    await new Promise((resolve) => setTimeout(resolve, SCAN_POLL_MS))
    const current = job(id)
    if (current?.state !== "pending-scan") return
    try {
      const ticket = await fetchIngestionTicket(scanResultId)
      failures = 0
      if (job(id)?.state !== "pending-scan") return
      const outcome = outcomeOfTicket(ticket, current.request)
      patch(id, outcome)
      if (outcome.state === "done" || outcome.state === "versioned")
        onScanned?.()
    } catch {
      // Not reachable just now - the scan carries on server-side; ask again next time,
      // but say so once it's been a while rather than look like it's still progressing.
      if (++failures === SCAN_POLL_FAILURES_SHOWN)
        patch(id, {
          message:
            "Can't reach the server to check the virus scan - still trying…",
        })
    }
  }
  pump()
}

/** The callback of the batch this job belongs to, if any. */
function onDoneOf(id: string) {
  for (const batch of batches.values())
    if (batch.remaining.has(id)) return batch.onDone
  return undefined
}

function outcomeOf(error: unknown): Partial<UploadJob> {
  if (error instanceof DOMException && error.name === "AbortError")
    return { state: "cancelled", message: "Cancelled" }
  if (error instanceof ApiError) {
    if (error.code === "DUPLICATE_UPLOADED_MEANWHILE")
      return {
        state: "duplicate",
        message: "Someone added the same file a moment ago.",
      }
    if (error.status === 422)
      return { state: "blocked", message: error.message }
    if (error.status === 413)
      return { state: "rejected", message: error.message || TOO_LARGE_MESSAGE }
    // The server says why: a type that isn't allowed, content that doesn't match its extension,
    // or a ZIP that's damaged, empty or past what the virus scanner can check.
    if (error.status === 415)
      return {
        state: "rejected",
        message: error.message || "This file type is not allowed.",
      }
    if (error.status === 403)
      return {
        state: "rejected",
        message: "You don't have permission to upload here.",
      }
    if (error.status === 0)
      return { state: "failed", message: "Could not reach the server." }
    return { state: "failed", message: error.message }
  }
  return { state: "failed", message: "Upload failed." }
}

function finish(id: string) {
  for (const [key, batch] of batches) {
    if (!batch.remaining.delete(id)) continue
    if (batch.remaining.size === 0) {
      batches.delete(key)
      batch.onDone?.()
    }
  }
}

/** Stops one file - sent bytes are discarded by the server. */
export function cancelUpload(id: string) {
  const current = job(id)
  if (!current) return
  if (current.state === "queued") {
    patch(id, { state: "cancelled", message: "Cancelled" })
    finish(id)
    pump()
  } else {
    controllers.get(id)?.abort()
  }
}

export function cancelAllUploads() {
  for (const d of useUploads.getState().downloads)
    if (d.state === "preparing") cancelDownload(d.id)
  for (const j of useUploads.getState().jobs)
    if (ACTIVE_STATES.includes(j.state)) cancelUpload(j.id)
}

/** Sends it again - "Upload anyway" for a file someone else added meanwhile. */
export function retryUpload(id: string) {
  const current = job(id)
  if (!current || !RETRYABLE.includes(current.state)) return
  const request =
    current.request.kind === "new" && current.state === "duplicate"
      ? { ...current.request, refuseDuplicates: false }
      : current.request
  patch(id, { state: "queued", loaded: 0, message: undefined, request })
  pump()
}

/** Closes the panel - only once nothing is still going. */
export function clearUploads() {
  const { jobs, downloads } = useUploads.getState()
  if (
    jobs.some((j) => ACTIVE_STATES.includes(j.state)) ||
    downloads.some((d) => d.state === "preparing")
  )
    return
  useUploads.setState({
    jobs: [],
    downloads: [],
    collapsed: false,
    secondsLeft: null,
  })
}

export function setUploadsCollapsed(collapsed: boolean) {
  useUploads.setState({ collapsed })
}

/** How often a download being prepared is checked on. */
const POLL_MS = 2000
const polling = new Set<string>()

function patchDownload(id: string, change: Partial<DownloadJob>) {
  useUploads.setState((s) => ({
    downloads: s.downloads.map((d) => (d.id === id ? { ...d, ...change } : d)),
  }))
}

function download(id: string) {
  return useUploads.getState().downloads.find((d) => d.id === id)
}

/**
 * Downloads a folder or several documents as one ZIP: shown in the panel as
 * "Preparing download" while the server builds it, then the browser saves it
 * - the way Google Drive does it.
 */
export function startZipDownload(
  request: CreateExportRequestDto,
  name: string
) {
  const id = `download-${++nextJobId}`
  useUploads.setState((s) => ({
    downloads: [...s.downloads, { id, name, state: "preparing", request }],
    collapsed: false,
  }))
  void prepare(id)
}

async function prepare(id: string) {
  const current = download(id)
  if (!current) return
  polling.add(id)
  try {
    let job = await requestExport(current.request)
    patchDownload(id, { documentCount: job.documentCount })
    while (job.status === "pending" || job.status === "processing") {
      await new Promise((resolve) => setTimeout(resolve, POLL_MS))
      if (!polling.has(id)) return // cancelled
      job = await fetchExportJob(job.exportJobId)
    }
    if (!polling.has(id)) return
    if (job.status !== "completed" || !job.downloadAvailable) {
      patchDownload(id, {
        state: "failed",
        message: job.failureReason ?? "The download couldn't be prepared.",
      })
      return
    }
    // The session cookie must be fresh: the browser fetches the ZIP itself.
    await refreshSession()
    saveFromServer(exportDownloadUrl(job.exportJobId))
    patchDownload(id, {
      state: "done",
      message: job.failureReason ?? undefined,
    })
  } catch (error) {
    if (polling.has(id))
      patchDownload(id, {
        state: "failed",
        message: friendlyMessage(error, "The download couldn't be prepared."),
      })
  } finally {
    polling.delete(id)
  }
}

/**
 * Lets the browser download the file itself, straight to disk however big,
 * without leaving the page - a hidden frame, so an upload in progress here
 * isn't interrupted. The server answers with Content-Disposition: attachment.
 */
function saveFromServer(url: string) {
  const frame = document.createElement("iframe")
  frame.hidden = true
  frame.src = url
  document.body.appendChild(frame)
  setTimeout(() => frame.remove(), 60_000)
}

/** Stops waiting for it - nothing is downloaded. */
export function cancelDownload(id: string) {
  if (!polling.delete(id)) return
  patchDownload(id, { state: "cancelled", message: "Cancelled" })
}

/** Prepares it again - e.g. after it failed or was cancelled. */
export function retryDownload(id: string) {
  const current = download(id)
  if (!current || current.state === "preparing") return
  patchDownload(id, { state: "preparing", message: undefined })
  void prepare(id)
}
