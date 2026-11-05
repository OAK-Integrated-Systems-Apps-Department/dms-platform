import { describe, expect, it, vi } from "vitest"

import { createEditorBridge, editorOrigin, HIDDEN_BUTTONS, HIDDEN_COMMANDS, type EditorMessage } from "./editor-bridge"

const ORIGIN = "http://localhost:9980"

function setup() {
  const sent: Array<{ message: EditorMessage; to: string }> = []
  const post = vi.fn((message: EditorMessage, to: string) => void sent.push({ message, to }))
  return { bridge: createEditorBridge(ORIGIN, post), sent, post }
}

const status = (Status: string) => ({ MessageId: "App_LoadingStatus", Values: { Status } })

describe("editorOrigin", () => {
  it("is the scheme, host and port of the address the editor is loaded from - nothing more", () => {
    expect(editorOrigin("http://localhost:9980/browser/abc/cool.html?WOPISrc=x")).toBe("http://localhost:9980")
    expect(editorOrigin("https://docs.example.com/browser/abc/cool.html?")).toBe("https://docs.example.com")
  })

  it("is null for something that is not an address", () => {
    expect(editorOrigin("not a url")).toBeNull()
    expect(editorOrigin("")).toBeNull()
  })
})

describe("createEditorBridge", () => {
  it("answers the frame's 'ready' so the editor starts talking", () => {
    const { bridge, sent } = setup()
    expect(bridge.onMessage({ origin: ORIGIN, data: JSON.stringify(status("Frame_Ready")) })).toBe("frame-ready")
    expect(sent).toHaveLength(1)
    expect(sent[0].message.MessageId).toBe("Host_PostmessageReady")
    expect(sent[0].to).toBe(ORIGIN)
  })

  it("once the document has loaded, hides the security label and every way of signing - and nothing else", () => {
    const { bridge, sent } = setup()
    expect(bridge.onMessage({ origin: ORIGIN, data: status("Document_Loaded") })).toBe("document-loaded")

    const buttons = sent.filter((s) => s.message.MessageId === "Hide_Button").map((s) => s.message.Values?.id)
    const commands = sent.filter((s) => s.message.MessageId === "Hide_Command").map((s) => s.message.Values?.id)
    expect(buttons).toEqual([...HIDDEN_BUTTONS])
    expect(commands).toEqual([...HIDDEN_COMMANDS])
    expect(buttons).toContain("securitylabel")
    expect(commands).toContain(".uno:SecurityLabel")
    expect(commands).toContain(".uno:Signature")
    expect(commands).toContain(".uno:InsertSignatureLine")
    // Download, Save and Print are untouched here.
    expect([...buttons, ...commands].join(" ")).not.toMatch(/save|print|export|download/i)
    expect(sent.every((s) => s.to === ORIGIN)).toBe(true) // only ever addressed to the editor's own origin
  })

  it("ignores a message from any other origin - a page cannot make it do anything", () => {
    const { bridge, post } = setup()
    expect(bridge.onMessage({ origin: "https://evil.example", data: status("Document_Loaded") })).toBeNull()
    expect(bridge.onMessage({ origin: "null", data: status("Frame_Ready") })).toBeNull()
    expect(post).not.toHaveBeenCalled()
  })

  it("ignores garbage, other messages and other statuses without throwing", () => {
    const { bridge, post } = setup()
    for (const data of ["not json", "42", "null", 7, null, undefined, {}, { MessageId: "Other" }, status("Doc_Loading"), []]) {
      expect(bridge.onMessage({ origin: ORIGIN, data })).toBeNull()
    }
    expect(post).not.toHaveBeenCalled()
  })

  it("can ask the editor to save and stay open, and recognises its answer", () => {
    const { bridge, sent } = setup()
    bridge.saveNow()
    expect(sent).toHaveLength(1)
    expect(sent[0].message.MessageId).toBe("Action_Save")
    expect(sent[0].message.Values).toEqual({ DontTerminateEdit: "true", DontSaveIfUnmodified: "true" })
    expect(sent[0].to).toBe(ORIGIN)

    expect(bridge.onMessage({ origin: ORIGIN, data: { MessageId: "Action_Save_Resp", Values: { success: true } } })).toBe(
      "save-finished"
    )
    expect(bridge.onMessage({ origin: "https://evil.example", data: { MessageId: "Action_Save_Resp" } })).toBeNull()
  })

  it("hideControls can be repeated safely (the editor's toolbar can finish building after the first ask)", () => {
    const { bridge, sent } = setup()
    bridge.hideControls()
    bridge.hideControls()
    expect(sent).toHaveLength(2 * (HIDDEN_BUTTONS.length + HIDDEN_COMMANDS.length))
  })
})
