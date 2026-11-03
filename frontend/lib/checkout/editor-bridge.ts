/**
 * The page's side of the conversation with the online editor frame (Collabora's postMessage API).
 *
 * Why it exists: the editor has its own buttons for things the DMS already does, and some of them must not be
 * there. "Security label" duplicates the DMS's own classification handling, and "Signature" (plus Insert > Signature
 * line) would let a document be signed outside the DMS's signature records and locks. Collabora has no server
 * setting for hiding these - but it will hide any control by id when the HOSTING PAGE asks. (Download, Print-less
 * export and Repair are hidden by flags in the file info instead, and signing is also switched off in the engine's
 * own config; this is the last layer for the controls that have neither.)
 *
 * The protocol: the frame says {App_LoadingStatus: Frame_Ready}, the page answers Host_PostmessageReady, and once
 * {Document_Loaded} arrives the toolbar exists and can be told what to hide.
 *
 * Trust: a message is acted on only if it comes from the editor's own origin. Nothing in it is ever executed or
 * rendered; the only thing it can cause is these fixed hide commands being sent back.
 */

/** Toolbar buttons to hide, by the id Collabora gives them. */
export const HIDDEN_BUTTONS = ["securitylabel", "signature"] as const

/** Every control for these commands to hide, wherever it appears (ribbon, menus, context menus). */
export const HIDDEN_COMMANDS = [".uno:SecurityLabel", ".uno:Signature", ".uno:InsertSignatureLine"] as const

/** The editor's origin (scheme://host:port) from the address the page loads it from; null if that is not a URL. */
export function editorOrigin(editorUrl: string): string | null {
  try {
    return new URL(editorUrl).origin
  } catch {
    return null
  }
}

export type EditorMessage = { MessageId: string; SendTime: number; Values?: Record<string, string> }
export type PostToEditor = (message: EditorMessage, targetOrigin: string) => void

/** What an incoming message meant to the page. */
export type EditorEvent = "frame-ready" | "document-loaded" | "save-finished" | null

/**
 * @param origin the editor's origin - messages from anywhere else are ignored, and ours are addressed only to it
 * @param post   sends one message to the editor frame (the component passes the iframe's contentWindow.postMessage)
 */
export function createEditorBridge(origin: string, post: PostToEditor) {
  const send = (MessageId: string, Values?: Record<string, string>) =>
    post({ MessageId, SendTime: Date.now(), ...(Values ? { Values } : {}) }, origin)

  /** Safe to call more than once: hiding something already hidden is a no-op for the editor. */
  function hideControls() {
    for (const id of HIDDEN_BUTTONS) send("Hide_Button", { id })
    for (const id of HIDDEN_COMMANDS) send("Hide_Command", { id })
  }

  /**
   * Asks the editor to save now (the same as Ctrl+S: a new version, scanned like any upload) and to stay open.
   * Does nothing if there are no unsaved changes. The editor answers with Action_Save_Resp -> "save-finished".
   */
  function saveNow() {
    send("Action_Save", { DontTerminateEdit: "true", DontSaveIfUnmodified: "true" })
  }

  return {
    hideControls,
    saveNow,

    /** Feed it every `message` event on the page; it decides whether it is the editor talking and what to do. */
    onMessage(event: { origin: string; data: unknown }): EditorEvent {
      if (event.origin !== origin) return null
      let message: unknown = event.data
      if (typeof message === "string") {
        try {
          message = JSON.parse(message)
        } catch {
          return null
        }
      }
      if (!message || typeof message !== "object") return null
      const { MessageId, Values } = message as { MessageId?: unknown; Values?: { Status?: unknown } }
      if (MessageId === "Action_Save_Resp") return "save-finished"
      if (MessageId !== "App_LoadingStatus") return null

      if (Values?.Status === "Frame_Ready") {
        send("Host_PostmessageReady")
        return "frame-ready"
      }
      if (Values?.Status === "Document_Loaded") {
        hideControls()
        return "document-loaded"
      }
      return null
    },
  }
}
