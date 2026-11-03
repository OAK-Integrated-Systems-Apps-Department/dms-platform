"use client"

import { useEffect, useRef, useState, type ReactNode } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ArrowLeft, Loader2, Maximize2 } from "lucide-react"
import { toast } from "sonner"

import { DocumentBreadcrumb } from "@/components/documents/document-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { useDocument } from "@/lib/documents/use-document"
import { discardCheckout, startEditSession, type EditingSessionDto, type EditorLayout } from "@/lib/checkout/api"
import { createEditorBridge, editorOrigin } from "@/lib/checkout/editor-bridge"
import { beginSessionKeepAlive } from "@/lib/auth/use-proactive-session-refresh"
import { useCheckoutStatus, useUserPreferences } from "@/lib/checkout/use-checkout"

import { LastEdited } from "./last-edited"

const BUTTON =
  "flex items-center gap-1.5 rounded-md border border-[var(--border-strong)] bg-card px-3.5 py-2 text-[12.5px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60"

/** How long "Open full screen" waits for the editor to confirm its save before moving on anyway. */
const SAVE_WAIT_MS = 4_000

/**
 * The embedded online editor (2.10, CICO-FR-006): asks the backend to open a session - which checks every rule
 * (access, signature lock, legal hold, someone else's check-out, engine up, file type) and, in exclusive mode,
 * takes the check-out for you - then hands the document to the editing engine in an iframe instead of rendering an
 * editor of our own.
 *
 * The access token goes to the engine in a form POST aimed at the iframe, not in the URL, so it never lands in
 * history, referrers or logs. When the engine is off, down or cannot edit this file type, the backend refuses
 * with a message that says to use Check out and download instead (CICO-NFR-002) - shown here with a way back,
 * never a blank page.
 *
 * It opens inside the DMS page, under the usual menus. "Open full screen" saves any unsaved changes, then continues in a
 * new tab that fills the whole window (fullScreen), leaving a note here so the document is never edited twice at once.
 * A user can also choose on My Profile to have it open full screen straight away.
 */
export function DocumentEditor({ documentId, fullScreen = false }: { documentId: string; fullScreen?: boolean }) {
  const router = useRouter()
  const [session, setSession] = useState<EditingSessionDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [releasing, setReleasing] = useState(false)
  const [expanding, setExpanding] = useState(false)
  /** The editor was continued in a full-screen tab, so this one no longer holds an editor of its own. */
  const [movedToTab, setMovedToTab] = useState(false)
  const bridgeRef = useRef<ReturnType<typeof createEditorBridge> | null>(null)
  const saveConfirmed = useRef<(() => void) | null>(null)
  const form = useRef<HTMLFormElement>(null)
  const frame = useRef<HTMLIFrameElement>(null)
  const started = useRef(false)
  const back = `/documents/${documentId}`
  const { data: document } = useDocument(documentId)
  // Polled every 2 s, so "last edited by ..." moves on by itself as soon as a save becomes the new version.
  const { data: lock } = useCheckoutStatus(documentId)

  // A full-screen tab is always full page. Otherwise it is the user's preference (inside the DMS page unless they chose
  // full page on My Profile), read once and then kept: a preference changed elsewhere while this page is open must not
  // swap the page around the editor, which would reload the frame and lose anything not yet saved with Ctrl+S.
  const preferences = useUserPreferences()
  const [layout, setLayout] = useState<EditorLayout | null>(null)
  if (layout === null && (fullScreen || !preferences.isPending)) {
    setLayout(fullScreen ? "fullpage" : (preferences.data?.editorLayout ?? "inline"))
  }
  const fullPage = layout === "fullpage"

  // The editor is a cross-origin iframe: typing in it never reaches this page, so the page's own "is the user active"
  // check would see an idle user after 10 minutes and stop renewing the sign-in, which then idles out at 30. While the
  // editor is open the person is working, so keep the session alive explicitly (as uploads do).
  useEffect(() => beginSessionKeepAlive(), [])

  // React strict mode runs effects twice in development; one session is enough.
  useEffect(() => {
    if (started.current) return
    started.current = true
    startEditSession(documentId)
      .then(setSession)
      .catch((e) => setError(friendlyMessage(e, "Could not open the online editor.")))
  }, [documentId])

  // (Re)load the document into the frame: when the session arrives, and again if the editor is brought back here.
  useEffect(() => {
    if (session && !movedToTab) form.current?.submit()
  }, [session, movedToTab])

  // Talk to the editor frame: hide the controls the DMS has its own versions of (security label, signing) as soon as
  // the document has loaded. Only messages from the editor's own frame and origin are acted on - see editor-bridge.ts.
  useEffect(() => {
    if (!session) return
    const origin = editorOrigin(session.editorUrl)
    if (!origin) return
    const bridge = createEditorBridge(origin, (message, to) =>
      frame.current?.contentWindow?.postMessage(JSON.stringify(message), to)
    )
    bridgeRef.current = bridge
    const timers: number[] = []
    function onMessage(event: MessageEvent) {
      if (event.source !== frame.current?.contentWindow) return
      const happened = bridge.onMessage(event)
      if (happened === "document-loaded") {
        // The toolbar can finish building just after the document loads, so ask again a little later.
        timers.push(window.setTimeout(bridge.hideControls, 1500), window.setTimeout(bridge.hideControls, 5000))
      } else if (happened === "save-finished") {
        saveConfirmed.current?.()
      }
    }
    window.addEventListener("message", onMessage)
    return () => {
      bridgeRef.current = null
      window.removeEventListener("message", onMessage)
      timers.forEach((t) => window.clearTimeout(t))
    }
  }, [session])

  async function finish() {
    setReleasing(true)
    try {
      // Saves already made are kept as versions; this only gives the lock up (exclusive mode).
      await discardCheckout(documentId)
    } catch {
      // Already released (expired or force-released) - nothing more to do.
    }
    router.push(back)
  }

  /**
   * "Open full screen": save what is unsaved (so the new tab opens the latest version), open the full-screen tab, and
   * stop editing here - two editors on one document at once would overwrite each other.
   */
  async function expand() {
    setExpanding(true)
    await new Promise<void>((resolve) => {
      const timer = window.setTimeout(resolve, SAVE_WAIT_MS)
      saveConfirmed.current = () => {
        window.clearTimeout(timer)
        resolve()
      }
      if (bridgeRef.current) bridgeRef.current.saveNow()
      else saveConfirmed.current()
    })
    saveConfirmed.current = null
    const tab = window.open(`/documents/${documentId}/edit?layout=full`, "_blank")
    setExpanding(false)
    if (!tab) {
      toast.error("Your browser blocked the new tab. Allow pop-ups for this site, then try again.")
      return
    }
    setMovedToTab(true)
  }

  /** The page around whatever is shown: the whole window, or the normal DMS page with its breadcrumb. */
  function frameAround(children: ReactNode) {
    if (fullPage) {
      return (
        <div className="fixed inset-0 z-50 flex flex-col bg-background" data-testid="editor-fullpage">
          {children}
        </div>
      )
    }
    return (
      <DashboardShell initialActiveLabel="My Documents">
        <DocumentBreadcrumb
          className="mb-3"
          documentId={documentId}
          title={document?.title}
          folderId={document?.folderId}
          trailing="Edit online"
        />
        {children}
      </DashboardShell>
    )
  }

  if (error) {
    return frameAround(
      <div className={fullPage ? "m-auto w-full max-w-xl px-4" : undefined}>
        <div className="max-w-xl rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[22px] text-[13.5px] text-[var(--rust)]">
          <p className="font-semibold">The online editor could not be opened.</p>
          <p className="mt-1.5">{error}</p>
          <Link href={back} className={`${BUTTON} mt-4 w-fit`}>
            <ArrowLeft size={14} /> Back to the document
          </Link>
        </div>
      </div>
    )
  }

  if (!session || layout === null) {
    return frameAround(
      <div className={fullPage ? "m-auto" : undefined}>
        <LoadingState message="Opening the editor…" />
      </div>
    )
  }

  const hint =
    session.mode === "exclusive"
      ? "You have this document checked out. Press Ctrl+S to save a version; autosaves are not kept."
      : "Everyone with edit access can be in this document at once. Press Ctrl+S to save a version."

  const actions = (
    <div className="flex shrink-0 gap-2">
      {!fullPage && !movedToTab && (
        <button type="button" className={BUTTON} disabled={expanding} onClick={() => void expand()}>
          {expanding ? <Loader2 size={14} className="animate-spin" /> : <Maximize2 size={14} />}
          Open full screen
        </button>
      )}
      <Link href={back} className={BUTTON}>
        <ArrowLeft size={14} /> Back
      </Link>
      {session.mode === "exclusive" && (
        <button type="button" className={BUTTON} disabled={releasing} onClick={finish}>
          {releasing && <Loader2 size={14} className="animate-spin" />}
          Done editing (release lock)
        </button>
      )}
    </div>
  )

  const editor = movedToTab ? (
    <div className="flex flex-1 flex-col items-start gap-3 rounded-lg border border-border bg-card p-[22px] text-[13.5px]">
      <p className="font-semibold">This document is open full screen in another tab.</p>
      <p className="text-muted-foreground">
        Carry on there. Your changes were saved before it opened, and the document stays checked out to you until you
        press Done editing.
      </p>
      <button type="button" className={BUTTON} onClick={() => setMovedToTab(false)}>
        Edit here instead
      </button>
    </div>
  ) : (
    <>
      <form ref={form} action={session.editorUrl} method="post" target="editor-frame" hidden>
        <input type="hidden" name="access_token" value={session.accessToken} readOnly />
        <input type="hidden" name="access_token_ttl" value={session.accessTokenExpiresAtMillis} readOnly />
      </form>
      <iframe
        ref={frame}
        name="editor-frame"
        title="Online document editor"
        className={
          fullPage
            ? "min-h-0 w-full flex-1 border-0 bg-card"
            : "min-h-0 w-full flex-1 rounded-lg border border-border bg-card"
        }
        allow="clipboard-read; clipboard-write; fullscreen"
      />
    </>
  )

  if (fullPage) {
    return frameAround(
      <>
        <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1.5 border-b border-border bg-card px-4 py-2">
          <div className="flex min-w-0 flex-col gap-0.5">
            <p className="truncate text-[14px] font-semibold text-foreground">{document?.title ?? "Document"}</p>
            <p className="truncate text-[12px] text-muted-foreground" title={hint}>
              {hint}
            </p>
            {lock && <LastEdited status={lock} />}
          </div>
          {actions}
        </header>
        {editor}
      </>
    )
  }

  return frameAround(
    <div className="flex h-[calc(100vh-170px)] min-h-[440px] flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <p className="text-[12.5px] text-muted-foreground">{hint}</p>
          {lock && <LastEdited status={lock} />}
        </div>
        {actions}
      </div>
      {editor}
    </div>
  )
}
