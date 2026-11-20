import { formatBytes } from "@/lib/quarantine/quarantine-badges"

/**
 * What the Scan Documents form (`/documents/scan`) accepts, and the checks it runs before anything is sent (FE-L2).
 *
 * These are the formats OCR works on - a deliberate subset of what dms-core's ingestion gate accepts overall - and the
 * list is only a courtesy: it spares someone a long upload that was always going to be refused, and a clear message in
 * place of a bare 415. dms-core stays the authority: it checks the extension again and then the file's actual content
 * (magic bytes), so a file named `report.exe.pdf` passes here and is still refused there if it is not a PDF. Nothing
 * here looks at the browser-reported MIME type (it is not authoritative) or tries to repeat the content check.
 *
 * NO SIZE LIMIT, on purpose: upload size is unlimited by an approved product decision (DOC-03), so nothing here ever
 * compares a size to a threshold. The only size rule is that an empty file has nothing to scan.
 */

/** The accepted extensions, lower-case, with the dot - the form's `accept` list and the check below read this one list. */
export const SCAN_FILE_EXTENSIONS = [".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff"] as const

/** For `<input accept>`. Advisory only (the picker's "All files" option and a dropped file get past it) - hence `validateScanFile`. */
export const SCAN_FILE_ACCEPT = SCAN_FILE_EXTENSIONS.join(",")

/** The line under the file field. */
export const SCAN_FILE_HINT = "PDF, PNG, JPEG, or TIFF."

/** What a File gives us that matters here; a File satisfies it, and so does a plain object in a test. */
export interface ScanFileLike {
  name: string
  size: number
}

/**
 * The last extension of the file's name, lower-case with the dot, or null when there is none - decided exactly as
 * dms-core's `FileTypeInspector.extensionOf` does, so the two cannot disagree about what "the extension" is: path
 * separators are dropped, a leading dot (`.pdf`) is a hidden-file name with no extension, and a trailing dot (`scan.`)
 * has none either.
 */
function extensionOf(filename: string): string | null {
  const name = filename.replace(/\\/g, "/")
  const base = name.slice(name.lastIndexOf("/") + 1)
  const dot = base.lastIndexOf(".")
  if (dot <= 0 || dot === base.length - 1) return null
  return base.slice(dot).toLowerCase()
}

/** The user-facing reason the file cannot be uploaded, or null when it can. */
export function validateScanFile(file: ScanFileLike): string | null {
  const extension = extensionOf(file.name)
  if (extension === null || !(SCAN_FILE_EXTENSIONS as readonly string[]).includes(extension)) {
    return `"${file.name}" is not a supported file type. Choose a ${SCAN_FILE_HINT.replace(/\.$/, "")} file.`
  }
  if (file.size === 0) {
    return `"${file.name}" is empty, so there is nothing to scan. Choose a different file.`
  }
  return null
}

/** The size line under a file's name: human-readable, and never "NaN" or "undefined". */
export function describeUploadSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return "unknown size"
  return formatBytes(bytes)
}