/**
 * Every watermark limit, default and supported format in ONE file.
 *
 * These mirror the backend's `dms.watermark.*` configuration
 * (WatermarkProperties / application.yml) and the validation annotations on
 * WatermarkRequest / WatermarkTemplateRequest. They exist so the dialog can
 * refuse obviously-bad input before a round trip and show the real defaults
 * next to each control - NOT as a second source of truth. The backend
 * validates everything again and its answer is final; if an operator tunes
 * `dms.watermark.style.*` away from these, the dialog's inline hint goes
 * stale but nothing breaks, because the server still decides.
 *
 * Keeping them here rather than inline at each control is the point: the
 * same numbers are needed by the zod schema, the slider bounds, the helper
 * text and the admin template form, and four copies would drift.
 */

/** Content types the backend can watermark - WatermarkServiceFactory's registered services. */
export const WATERMARK_SUPPORTED_MIME_TYPES = [
  "application/pdf",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  "image/jpeg",
  "image/png",
] as const

/**
 * The same six by extension, for rows whose mimeType is missing. The
 * backend never trusts an extension - it re-verifies the bytes and answers
 * 415 - so this is only ever used to disable a control early, never to
 * decide that something IS watermarkable.
 */
export const WATERMARK_SUPPORTED_EXTENSIONS = [
  "pdf",
  "docx",
  "xlsx",
  "pptx",
  "jpg",
  "jpeg",
  "png",
] as const

/** How the refusal is worded, matching WatermarkServiceFactory#supportedFormats. */
export const WATERMARK_SUPPORTED_LABEL =
  "PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), JPEG and PNG"

export const WATERMARK_PLACEMENTS = [
  { value: "tiled", label: "Tiled", hint: "Repeated across the whole page. Survives cropping." },
  { value: "centered", label: "Centered", hint: "One mark in the middle of the page." },
  { value: "corner", label: "Corner", hint: "A small badge in the top-right, clear of the text." },
  {
    value: "header-footer",
    label: "Header & footer",
    hint: "A line along the top margin and another along the bottom.",
  },
] as const

export const WATERMARK_PAGE_SCOPES = [
  { value: "all-pages", label: "All pages" },
  { value: "first-page-only", label: "First page only" },
] as const

/**
 * What "first page" means per format. Spelled out because it genuinely
 * differs - a spreadsheet has no pages until it is printed - and a user
 * choosing it for an .xlsx would otherwise be guessing.
 */
export const FIRST_PAGE_ONLY_BY_FORMAT =
  "PDF and Word: page 1. Excel: the first visible sheet. PowerPoint: slide 1. Images: the whole image."

/**
 * Placeholders, resolved once when the copy is requested and frozen onto
 * the job. Order matches the backend's registry so the chips read the same
 * way the API docs do.
 */
export const WATERMARK_PLACEHOLDERS = [
  { token: "{username}", hint: "Your display name" },
  { token: "{userEmail}", hint: "Your email address" },
  { token: "{date}", hint: "When you asked for the copy, in UTC" },
  { token: "{documentId}", hint: "The document's id" },
  { token: "{documentVersion}", hint: "The version number being copied" },
  { token: "{watermarkId}", hint: "A short code that traces the copy back to the audit log" },
] as const

/** Mirrors WatermarkProperties.Style - the system defaults a request falls through to. */
export const WATERMARK_STYLE_DEFAULTS = {
  fontSize: 40,
  opacity: 25,
  rotation: -45,
  color: "#808080",
  placement: "tiled",
  pageScope: "all-pages",
} as const

/**
 * Mirrors WatermarkProperties.Style's bounds. Opacity's floor is 5 rather
 * than 0 deliberately: an invisible watermark is indistinguishable from a
 * missing one, and someone who set it to zero would believe their copy was
 * marked when it wasn't.
 */
export const WATERMARK_STYLE_LIMITS = {
  fontSize: { min: 8, max: 200 },
  opacity: { min: 5, max: 100 },
  rotation: { min: -90, max: 90 },
} as const

/** `#rrggbb` only - no names, no alpha channel (opacity is its own setting). */
export const WATERMARK_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/

/**
 * The raw text field's cap (WatermarkRequest/@Size). Note the RESOLVED text
 * is capped lower (`dms.watermark.style.max-text-length`, 200 by default),
 * so a short template full of placeholders can still be refused with a 422
 * after substitution - which is why the dialog shows the server's message
 * rather than claiming anything under 500 will work.
 */
export const WATERMARK_TEXT_MAX_LENGTH = 500

/** WatermarkTemplateRequest/@Size on the label. */
export const WATERMARK_TEMPLATE_LABEL_MAX_LENGTH = 100

/** How long a finished copy stays downloadable - `dms.watermark.output-ttl`. */
export const WATERMARK_OUTPUT_TTL_HINT = "24 hours"

/**
 * The limitation note shown in the dialog footer. Stated in the UI, not
 * only the API docs, because the person choosing to send a watermarked copy
 * is the one who needs to know what it does and does not buy them.
 */
export const WATERMARK_LIMITATIONS = [
  "A watermark deters sharing - it does not prevent removal.",
  "Word, Excel and PowerPoint copies can be edited, so a recipient can delete the mark.",
  "The in-app preview may not show Office watermarks; open the downloaded file to check.",
] as const
