"use client"

import { useEffect, useRef, useState } from "react"

import { friendlyMessage } from "@/lib/api-error"
import { beginSessionKeepAlive } from "@/lib/auth/use-proactive-session-refresh"

import {
  createUploadSession,
  fetchUploadSession,
  MAX_ARCHIVE_BYTES,
  MAX_DIRECT_ARCHIVE_BYTES,
  unpackUploadSession,
  uploadBulkImportArchive,
  uploadSessionChunk,
  type ImportArchiveReceivedResponseDto,
} from "./api"

/** Attempts per chunk before the upload pauses and offers "Resume". */
const CHUNK_ATTEMPTS = 3

export type ArchiveUploadState =
  | { kind: "idle" }
  /** `progress` is 0..1 while sending; null when it can't be measured (a single-request upload). */
  | { kind: "sending"; fileName: string; progress: number | null }
  | { kind: "unpacking"; fileName: string }
  | { kind: "done"; fileName: string; result: ImportArchiveReceivedResponseDto }
  /** `resumable`: a chunked upload stopped part way and can carry on from where it got to. */
  | { kind: "error"; fileName: string; message: string; resumable: boolean }

/**
 * Sends one ZIP of a batch's files. Up to ~95 MB it goes in one request;
 * larger ZIPs (up to 2 GB) go through a resumable upload session in
 * chunks, each retried a few times - if the connection still drops, the
 * upload pauses and `resume()` carries on from the last byte the server
 * has. Either way the server then unpacks the ZIP into the batch's rows.
 */
export function useArchiveUpload(onFinished: (result: ImportArchiveReceivedResponseDto) => void) {
  const [state, setState] = useState<ArchiveUploadState>({ kind: "idle" })
  const sessionRef = useRef<{ batchId: string; file: File; sessionId: string } | null>(null)
  const cancelledRef = useRef(false)
  const onFinishedRef = useRef(onFinished)

  useEffect(() => {
    onFinishedRef.current = onFinished
  }, [onFinished])

  useEffect(() => {
    const cancelled = cancelledRef
    cancelled.current = false
    return () => {
      cancelled.current = true
    }
  }, [])

  async function upload(batchId: string, file: File) {
    if (file.size > MAX_ARCHIVE_BYTES) {
      setState({ kind: "error", fileName: file.name, message: "The ZIP is larger than 2 GB.", resumable: false })
      return
    }
    const endKeepAlive = beginSessionKeepAlive()
    try {
      if (file.size <= MAX_DIRECT_ARCHIVE_BYTES) {
        setState({ kind: "sending", fileName: file.name, progress: null })
        finish(file, await uploadBulkImportArchive(batchId, file))
        return
      }
      setState({ kind: "sending", fileName: file.name, progress: 0 })
      const session = await createUploadSession(file)
      sessionRef.current = { batchId, file, sessionId: session.uploadSessionId }
      await sendChunks(batchId, file, session.uploadSessionId, session.receivedBytes, session.maxChunkBytes)
    } catch (error) {
      fail(file, error)
    } finally {
      endKeepAlive()
    }
  }

  /** Carries a paused chunked upload on from the bytes the server already has. */
  async function resume() {
    const paused = sessionRef.current
    if (!paused) return
    const endKeepAlive = beginSessionKeepAlive()
    try {
      const session = await fetchUploadSession(paused.sessionId)
      setState({ kind: "sending", fileName: paused.file.name, progress: session.receivedBytes / paused.file.size })
      await sendChunks(paused.batchId, paused.file, paused.sessionId, session.receivedBytes, session.maxChunkBytes)
    } catch (error) {
      fail(paused.file, error)
    } finally {
      endKeepAlive()
    }
  }

  async function sendChunks(batchId: string, file: File, sessionId: string, from: number, chunkSize: number) {
    let offset = from
    while (offset < file.size) {
      if (cancelledRef.current) return
      const chunk = file.slice(offset, Math.min(offset + chunkSize, file.size))
      offset = await sendChunk(sessionId, offset, chunk)
      setState({ kind: "sending", fileName: file.name, progress: offset / file.size })
    }
    setState({ kind: "unpacking", fileName: file.name })
    finish(file, await unpackUploadSession(batchId, sessionId))
  }

  /** Sends one chunk, retrying; after a failure asks the server where it got to. Returns the new offset. */
  async function sendChunk(sessionId: string, offset: number, chunk: Blob): Promise<number> {
    let lastError: unknown
    for (let attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
      try {
        return (await uploadSessionChunk(sessionId, offset, chunk)).receivedBytes
      } catch (error) {
        lastError = error
        try {
          // The chunk may have landed before the connection dropped.
          const session = await fetchUploadSession(sessionId)
          if (session.receivedBytes >= offset + chunk.size) return session.receivedBytes
        } catch {
          // Still offline - wait and try again.
        }
        await new Promise((resolve) => setTimeout(resolve, 1000 * attempt))
      }
    }
    throw lastError
  }

  function finish(file: File, result: ImportArchiveReceivedResponseDto) {
    sessionRef.current = null
    setState({ kind: "done", fileName: file.name, result })
    onFinishedRef.current(result)
  }

  function fail(file: File, error: unknown) {
    setState({
      kind: "error",
      fileName: file.name,
      message: friendlyMessage(error, `Could not upload ${file.name}.`),
      resumable: sessionRef.current !== null,
    })
  }

  function reset() {
    sessionRef.current = null
    setState({ kind: "idle" })
  }

  const busy = state.kind === "sending" || state.kind === "unpacking"
  return { state, busy, upload, resume, reset }
}
