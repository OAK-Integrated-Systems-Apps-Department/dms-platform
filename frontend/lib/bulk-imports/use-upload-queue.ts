"use client"

import { useEffect, useRef, useState } from "react"

import { friendlyMessage } from "@/lib/api-error"
import { beginSessionKeepAlive } from "@/lib/auth/use-proactive-session-refresh"

import { uploadBulkImportFile, type ImportFileReceivedResponseDto } from "./api"

/** Matches spring.servlet.multipart.max-file-size on the backend. */
export const MAX_IMPORT_FILE_BYTES = 100 * 1024 * 1024

export type UploadQueueEntryState = "waiting" | "uploading" | "matched" | "error"

export interface UploadQueueEntry {
  id: number
  file: File
  state: UploadQueueEntryState
  /** Template row the file was matched to, once uploaded. */
  rowNumber?: number
  error?: string
}

interface PendingUpload {
  id: number
  batchId: string
  file: File
}

/**
 * Client-side queue that uploads bulk import files strictly one at a time.
 * The server holds a row lock on the batch for each upload, so parallel
 * requests would only queue up behind each other there (and risk
 * timeouts) - this keeps exactly one request in flight.
 */
export function useUploadQueue(onUploaded: (response: ImportFileReceivedResponseDto) => void) {
  const [entries, setEntries] = useState<UploadQueueEntry[]>([])
  const pendingRef = useRef<PendingUpload[]>([])
  const drainingRef = useRef(false)
  const nextIdRef = useRef(1)
  const onUploadedRef = useRef(onUploaded)
  /** Ends the session keep-alive held while the queue is uploading; null when idle. */
  const endKeepAliveRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    onUploadedRef.current = onUploaded
  }, [onUploaded])

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

  function update(id: number, patch: Partial<UploadQueueEntry>) {
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
        update(id, { state: "uploading" })
        try {
          const response = await uploadBulkImportFile(batchId, file)
          update(id, { state: "matched", rowNumber: response.rowNumber })
          onUploadedRef.current(response)
        } catch (error) {
          update(id, { state: "error", error: friendlyMessage(error, `Could not upload ${file.name}.`) })
        }
        next = pendingRef.current.shift()
      }
    } finally {
      drainingRef.current = false
      endKeepAlive()
    }
  }

  /** Queues files for `batchId`; files over the size limit are rejected without being sent. */
  function addFiles(batchId: string, files: File[]) {
    if (files.length === 0) return
    const added: UploadQueueEntry[] = files.map((file) => {
      const id = nextIdRef.current++
      if (file.size > MAX_IMPORT_FILE_BYTES) {
        return { id, file, state: "error", error: "Larger than 100 MB - this file can't be imported." }
      }
      pendingRef.current.push({ id, batchId, file })
      return { id, file, state: "waiting" }
    })
    setEntries((prev) => [...prev, ...added])
    void drain()
  }

  /** Drops everything still waiting and clears the list. An upload already in flight finishes on its own. */
  function reset() {
    pendingRef.current = []
    setEntries([])
  }

  const busy = entries.some((entry) => entry.state === "waiting" || entry.state === "uploading")

  return { entries, busy, addFiles, reset }
}
