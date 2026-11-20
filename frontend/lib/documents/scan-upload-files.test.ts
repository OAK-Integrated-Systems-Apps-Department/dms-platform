import { describe, expect, it } from "vitest"

import {
  SCAN_FILE_ACCEPT,
  SCAN_FILE_EXTENSIONS,
  describeUploadSize,
  validateScanFile,
  type ScanFileLike,
} from "./scan-upload-files"

const file = (name: string, size = 1234): ScanFileLike => ({ name, size })

describe("the accepted list", () => {
  it("is exactly the six approved extensions", () => {
    expect([...SCAN_FILE_EXTENSIONS]).toEqual([".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff"])
  })

  it("the accept string is those six, comma separated, and nothing else", () => {
    expect(SCAN_FILE_ACCEPT).toBe(".pdf,.png,.jpg,.jpeg,.tif,.tiff")
    expect(SCAN_FILE_ACCEPT.split(",")).toHaveLength(6)
  })

  it(".tif is included (it was missing before; the server accepts it)", () => {
    expect(SCAN_FILE_ACCEPT.split(",")).toContain(".tif")
  })
})

describe("validateScanFile - accepted", () => {
  it.each(["document.pdf", "image.png", "scan.jpg", "scan.jpeg", "scan.tif", "scan.tiff"])("accepts %s", (name) => {
    expect(validateScanFile(file(name))).toBeNull()
  })

  it.each(["DOCUMENT.PDF", "Image.PNG", "scan.JPG", "scan.JpEg", "SCAN.TIF", "scan.Tiff"])("matches the extension case-insensitively: %s", (name) => {
    expect(validateScanFile(file(name))).toBeNull()
  })

  it("uses the last extension, so multiple dots are fine", () => {
    expect(validateScanFile(file("my.scan.2026.01.pdf"))).toBeNull()
    expect(validateScanFile(file("a.b.c.tiff"))).toBeNull()
  })

  it("a double extension passes when the FINAL one is allowed - the server checks the content", () => {
    expect(validateScanFile(file("document.exe.pdf"))).toBeNull()
    expect(validateScanFile(file("invoice.zip.png"))).toBeNull()
  })

  it("unusual but valid names: spaces, unicode, parentheses, a path prefix", () => {
    expect(validateScanFile(file("Scan 2026 (final) – £.pdf"))).toBeNull()
    expect(validateScanFile(file("факс №3.tif"))).toBeNull()
    expect(validateScanFile(file("C:\\Users\\me\\Documents\\scan.pdf"))).toBeNull()
    expect(validateScanFile(file("folder/sub/scan.png"))).toBeNull()
  })
})

describe("validateScanFile - rejected", () => {
  it.each(["setup.exe", "archive.zip", "notes.txt", "photo.gif", "photo.bmp", "photo.webp", "photo.heic", "report.docx", "sheet.xlsx", "page.html"])(
    "rejects %s",
    (name) => {
      expect(validateScanFile(file(name))).toMatch(/not a supported file type/)
    },
  )

  it("rejects a missing extension", () => {
    expect(validateScanFile(file("scan"))).toMatch(/not a supported file type/)
    expect(validateScanFile(file("README"))).toMatch(/not a supported file type/)
  })

  it("rejects a name ending in a dot", () => {
    expect(validateScanFile(file("scan."))).toMatch(/not a supported file type/)
    expect(validateScanFile(file("scan.pdf."))).toMatch(/not a supported file type/)
  })

  it("rejects a leading-dot name, as the backend does (it has no extension)", () => {
    expect(validateScanFile(file(".pdf"))).toMatch(/not a supported file type/)
    expect(validateScanFile(file(".png"))).toMatch(/not a supported file type/)
  })

  it("rejects a final extension that is not allowed even when an earlier one is", () => {
    expect(validateScanFile(file("document.pdf.exe"))).toMatch(/not a supported file type/)
    expect(validateScanFile(file("scan.png.zip"))).toMatch(/not a supported file type/)
  })

  it("rejects a near miss that is not an allowed extension", () => {
    expect(validateScanFile(file("scan.pdfx"))).toMatch(/not a supported file type/)
    expect(validateScanFile(file("scan.tiff2"))).toMatch(/not a supported file type/)
    expect(validateScanFile(file("scanpdf"))).toMatch(/not a supported file type/)
  })

  it("rejects an empty name", () => {
    expect(validateScanFile(file(""))).toMatch(/not a supported file type/)
  })

  it("rejects a zero-byte file with its own message", () => {
    expect(validateScanFile(file("scan.pdf", 0))).toMatch(/empty/)
  })

  it("names the file in the message", () => {
    expect(validateScanFile(file("setup.exe"))).toContain("setup.exe")
    expect(validateScanFile(file("scan.pdf", 0))).toContain("scan.pdf")
  })

  it("names the supported types in the message", () => {
    expect(validateScanFile(file("setup.exe"))).toMatch(/PDF, PNG, JPEG, or TIFF/)
  })
})

describe("validateScanFile - no size limit", () => {
  it("accepts a very large file: size is never compared to a threshold", () => {
    // A File-like object that only claims a size - no multi-gigabyte buffer is allocated.
    for (const size of [1, 1024 ** 3, 50 * 1024 ** 3, 10 * 1024 ** 4, Number.MAX_SAFE_INTEGER]) {
      expect(validateScanFile({ name: "huge-scan.tiff", size })).toBeNull()
    }
  })

  it("size is irrelevant to the extension rule too", () => {
    expect(validateScanFile({ name: "huge.exe", size: 50 * 1024 ** 3 })).toMatch(/not a supported file type/)
  })
})

describe("validateScanFile - the browser's MIME type is not consulted", () => {
  it("only name and size are read, so a wrong or missing type changes nothing", () => {
    const wrongType = { name: "scan.pdf", size: 10, type: "application/x-msdownload" }
    const noType = { name: "scan.png", size: 10, type: "" }
    const rightTypeWrongName = { name: "scan.exe", size: 10, type: "application/pdf" }
    expect(validateScanFile(wrongType)).toBeNull()
    expect(validateScanFile(noType)).toBeNull()
    expect(validateScanFile(rightTypeWrongName)).toMatch(/not a supported file type/)
  })
})

describe("describeUploadSize", () => {
  it("is human-readable", () => {
    expect(describeUploadSize(0)).toBe("0 B")
    expect(describeUploadSize(512)).toBe("512 B")
    expect(describeUploadSize(2048)).toBe("2.0 KB")
    expect(describeUploadSize(2_345_678)).toBe("2.2 MB")
    expect(describeUploadSize(5 * 1024 ** 3)).toBe("5.0 GB")
  })

  it("never shows NaN or undefined", () => {
    for (const bad of [undefined, null, Number.NaN, Number.POSITIVE_INFINITY, -1]) {
      expect(describeUploadSize(bad as number | null | undefined)).toBe("unknown size")
    }
  })
})