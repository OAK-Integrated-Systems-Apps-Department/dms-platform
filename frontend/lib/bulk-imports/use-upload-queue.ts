"use client"

import { useEffect, useRef, useState } from "react"

import { ApiError, friendlyMessage } from "@/lib/api-error"
import { beginSessionKeepAlive } from "@/lib/auth/use-proactive-session-refresh"

import { uploadBulkImportFile } from "./api"

/** Matches spring.servlet.multipart.max-file-size on the backend. */
export const MAX_IMPORT_FILE_BYTES = 100 * 1024 * 1024

/**
 * `rejected`: the server (or the browser's size check) refused the file - final.
 * `failed`: no answer from the server (network error, timeout, 5xx) - it may or
 * may not have the file, so the entry can be retried with the same ID.
 */
export type UploadQueueEntryState = "waiting" | "uploading" | "accepted" | "rejected" | "failed"

export interface UploadQueueEntry {
  /** The file's clientFileId - new for every file added, kept for its retries. */
  id: string
  batchId: string
  file: File
  state: UploadQueueEntryState
  /** Why the file was rejected (the server's message, or the browser's size check), or why its upload failed. */
  error?: string
}

interface PendingUpload {
  id: string
  batchId: string
  file: File
}

/**
 * A new ID for a queued file. `crypto.randomUUID` only exists in secure
 * contexts (HTTPS or localhost); elsewhere a v4 UUID is built from
 * `crypto.getRandomValues`, which works everywhere.
 */
function newClientFileId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** A 4xx is the server's answer about the file; anything else (no response, 5xx) leaves it unknown. */
function isRejection(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500
}

/**
 * Client-side queue that uploads bulk import files strictly one at a time.
 * The server holds a row lock on the batch for each upload, so parallel
 * requests would only queue up behind each other there (and risk
 * timeouts) - this keeps exactly one request in flight. `onSettled` runs
 * after every request, accepted or not: a rejected upload is recorded on the
 * batch too, so its counts change either way.
 *
 * Every file gets its own ID, sent as clientFileId. A retry of a failed
 * upload resends the same ID, so if the server did take the file the first
 * time it answers as it did then instead of adding it twice.
 */
export function useUploadQueue(onSettled: () => void) {
  const [entries, setEntries] = useState<UploadQueueEntry[]>([])
  const pendingRef = useRef<PendingUpload[]>([])
  const drainingRef = useRef(false)
  const onSettledRef = useRef(onSettled)
  /** Ends the session keep-alive held while the queue is uploading; null when idle. */
  const endKeepAliveRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    onSettledRef.current = onSettled
  }, [onSettled])

  function endKeepAlive() {
    endKeepAliveRef.current?.()
    endKeepAliveRef.current = null
  }

  // Don't keep uploading into the batch after the screen is gone, or keep
  // the session alive for it.
  useEffect(() => {
    const pending = pendingRef
    const endKeepAlive = endKeepAliveRef
    return () => {
      pending.current = []
      endKeepAlive.current?.()
      endKeepAlive.current = null
    }
  }, [])

  function update(id: string, patch: Partial<UploadQueueEntry>) {
    setEntries((prev) => prev.map((entry) => (entry.id === id ? { ...entry, ...patch } : entry)))
  }

  async function drain() {
    if (drainingRef.current) return
    drainingRef.current = true
    // A long queue can outlast the session's idle timeout while the user
    // just watches, so keep it alive until the queue is empty.
    endKeepAliveRef.current ??= beginSessionKeepAlive()
    try {
      let next = pendingRef.current.shift()
      while (next) {
        const { id, batchId, file } = next
        update(id, { state: "uploading", error: undefined })
        try {
          await uploadBulkImportFile(batchId, file, id)
          update(id, { state: "accepted" })
        } catch (error) {
          if (isRejection(error)) {
            update(id, { state: "rejected", error: friendlyMessage(error, `Could not upload ${file.name}.`) })
          } else {
            update(id, { state: "failed", error: "Upload failed - no response from the server." })
          }
        }
        onSettledRef.current()
        next = pendingRef.current.shift()
      }
    } finally {
      drainingRef.current = false
      endKeepAlive()
    }
  }

  /** Queues files for `batchId`; files over the size limit are rejected without being sent (the server never sees them). */
  function addFiles(batchId: string, files: File[]) {
    if (files.length === 0) return
    const added: UploadQueueEntry[] = files.map((file) => {
      const id = newClientFileId()
      if (file.size > MAX_IMPORT_FILE_BYTES) {
        return { id, batchId, file, state: "rejected", error: "Larger than 100 MB - not uploaded." }
      }
      pendingRef.current.push({ id, batchId, file })
      return { id, batchId, file, state: "waiting" }
    })
    setEntries((prev) => [...prev, ...added])
    void drain()
  }

  /** Re-sends a failed upload with its original ID. Does nothing for an entry in any other state. */
  function retry(id: string) {
    const entry = entries.find((candidate) => candidate.id === id)
    if (!entry || entry.state !== "failed") return
    update(id, { state: "waiting", error: undefined })
    pendingRef.current.push({ id, batchId: entry.batchId, file: entry.file })
    void drain()
  }

  /** Drops everything still waiting and clears the list. An upload already in flight finishes on its own. */
  function reset() {
    pendingRef.current = []
    setEntries([])
  }

  const busy = entries.some((entry) => entry.state === "waiting" || entry.state === "uploading")

  return { entries, busy, addFiles, retry, reset }
}
