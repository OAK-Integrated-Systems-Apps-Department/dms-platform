import {
  WATERMARK_SUPPORTED_EXTENSIONS,
  WATERMARK_SUPPORTED_LABEL,
  WATERMARK_SUPPORTED_MIME_TYPES,
} from "./constants"

/** What the row knows about a document's file - either may be missing. */
export interface WatermarkableFile {
  mimeType?: string | null
  fileName?: string | null
}

export interface WatermarkSupport {
  supported: boolean
  /** Why not, for the disabled control's tooltip. Null when supported. */
  reason: string | null
}

const SUPPORTED_MIME = new Set<string>(WATERMARK_SUPPORTED_MIME_TYPES)
const SUPPORTED_EXT = new Set<string>(WATERMARK_SUPPORTED_EXTENSIONS)

function extensionOf(fileName: string | null | undefined): string | null {
  if (!fileName) return null
  const dot = fileName.lastIndexOf(".")
  if (dot < 0 || dot === fileName.length - 1) return null
  return fileName.slice(dot + 1).toLowerCase()
}

/**
 * Whether this document looks watermarkable, for enabling or disabling the
 * action before anything is sent.
 *
 * This is a COURTESY, never the decision. The backend re-verifies the bytes
 * themselves and answers 415 for anything it cannot handle - a file renamed
 * to `.pdf` is refused there no matter what this says. So the rule here is
 * deliberately asymmetric: the mime type is trusted when present (it is the
 * canonical one the backend recorded at ingestion), and the extension is
 * only ever used to disable, never to enable something the mime type
 * already ruled out. The worst this can do is grey out an action that would
 * have worked, which the 415 handling would have explained anyway.
 */
export function watermarkSupport(file: WatermarkableFile): WatermarkSupport {
  const mime = file.mimeType?.trim().toLowerCase() ?? null
  if (mime) {
    return SUPPORTED_MIME.has(mime)
      ? { supported: true, reason: null }
      : { supported: false, reason: unsupportedReason(extensionOf(file.fileName)) }
  }

  const extension = extensionOf(file.fileName)
  if (!extension) {
    // Nothing to judge by. Let the attempt through and let the backend
    // answer - better than hiding a working action on a row with sparse
    // metadata.
    return { supported: true, reason: null }
  }
  return SUPPORTED_EXT.has(extension)
    ? { supported: true, reason: null }
    : { supported: false, reason: unsupportedReason(extension) }
}

function unsupportedReason(extension: string | null): string {
  const what = extension ? `.${extension} files` : "This file type"
  return `${what} can't be watermarked. Supported: ${WATERMARK_SUPPORTED_LABEL}.`
}
