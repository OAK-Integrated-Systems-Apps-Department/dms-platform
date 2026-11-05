import { describe, expect, it } from "vitest"

import { canDrawAsPages, isOpenPolicy, OPEN_POLICY } from "./api"

describe("isOpenPolicy", () => {
  it("is true only when nothing is restricted", () => {
    expect(isOpenPolicy(OPEN_POLICY)).toBe(true)
    expect(isOpenPolicy({ ...OPEN_POLICY, print: "watermark" })).toBe(false)
    expect(isOpenPolicy({ ...OPEN_POLICY, download: "block" })).toBe(false)
    expect(isOpenPolicy({ ...OPEN_POLICY, copy: "block" })).toBe(false)
  })
})

describe("canDrawAsPages", () => {
  it("covers what the server draws as pages: PDFs, pictures and Office-style files", () => {
    for (const name of [
      "a.pdf",
      "A.PDF",
      "b.docx",
      "c.XLSX",
      "d.csv",
      "e.txt",
      "f.png",
      "g.jpeg",
      "h.tiff",
      "i.pptx",
      "j.rtf",
      "k.vsdx",
    ]) {
      expect(canDrawAsPages(name), name).toBe(true)
    }
  })

  it("is false for everything else - so such a file can't be previewed under a restricted policy", () => {
    for (const name of ["a.zip", "b.dwg", "c.eml", "d.mp4", "noextension", "", null]) {
      expect(canDrawAsPages(name), String(name)).toBe(false)
    }
  })
})
