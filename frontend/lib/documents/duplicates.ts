import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"
import { sha256HexStreaming } from "@/lib/documents/sha256-stream"

/** Mirrors DuplicateFinder.Match - a document an upload would duplicate. */
export interface DuplicateMatchDto {
  documentId: string
  title: string
  fileName: string | null
  folderId: string | null
  folderName: string | null
  documentTypeId: string
  /** the file's content equals one of its versions */
  sameContent: boolean
  /** byte-for-byte its current version - a new version would change nothing */
  identicalToCurrent: boolean
  /** its current file has the same name, in the same folder */
  sameName: boolean
  matchedVersion: string | null
  /** the user may add a version to it */
  canAddVersion: boolean
  /** nearly the same text (not the same file): how alike, 0-100 */
  similarity: number | null
}

/** Formats whose text the server reads to find similar documents (DocumentFingerprints). */
const READABLE = new Set([
  "pdf",
  "txt",
  "md",
  "csv",
  "tsv",
  "json",
  "xml",
  "rtf",
  "doc",
  "docx",
  "xls",
  "xlsx",
  "ppt",
  "pptx",
])
const MAX_SIMILAR_BYTES = 25 * 1024 * 1024

/** Whether a file can be checked for documents with nearly the same text. */
export function canCheckSimilar(file: File): boolean {
  const dot = file.name.lastIndexOf(".")
  const extension = dot < 0 ? "" : file.name.slice(dot + 1).toLowerCase()
  return READABLE.has(extension) && file.size <= MAX_SIMILAR_BYTES
}

/**
 * `POST /api/v1/documents/duplicates/similar` - documents with nearly the same
 * text (a re-saved copy, a light edit, the same report renamed). The file is
 * read on the server in memory only; nothing is stored.
 */
export async function findSimilarDocuments(
  file: File
): Promise<DuplicateMatchDto[]> {
  const form = new FormData()
  form.append("file", file)
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/duplicates/similar`,
    { method: "POST", body: form },
    "Could not look for similar documents."
  )
}

export interface DuplicateCheckResultDto {
  name: string
  matches: DuplicateMatchDto[]
}

/** What to do with one file that's already in the DMS. */
export type DuplicateChoice = "version" | "both" | "skip"

/** Up to this size the file is hashed by Web Crypto in one go; bigger ones are read in slices. */
const MAX_ONE_SHOT_HASH_BYTES = 256 * 1024 * 1024

/**
 * The file's SHA-256, worked out in the browser so nothing has to be uploaded
 * to find a duplicate. Null where the browser can't (no Web Crypto outside
 * HTTPS/localhost) - the check then goes by name only. Files over
 * MAX_ONE_SHOT_HASH_BYTES (of any size) are hashed slice
 * by slice instead, so a large file is never held in memory whole.
 */
export async function sha256Hex(file: File): Promise<string | null> {
  if (file.size > MAX_ONE_SHOT_HASH_BYTES) return sha256HexStreaming(file)
  if (typeof crypto === "undefined" || !crypto.subtle) return null
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (b) =>
    b.toString(16).padStart(2, "0")
  ).join("")
}

/** `POST /api/v1/documents/duplicates` - which of these files are already in the DMS. */
export async function checkDuplicates(
  folderId: string | null,
  files: { name: string; sha256: string | null }[]
): Promise<DuplicateCheckResultDto[]> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/duplicates`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ folderId, files }),
    },
    "Could not check for duplicates."
  )
}

/** The choice offered first: the same file again is skipped, a changed one with the same name becomes a version. */
export function suggestedChoice(match: DuplicateMatchDto): DuplicateChoice {
  // Nearly the same text, but not the same file: probably a revision - ask, defaulting to keeping both.
  if (match.similarity !== null && !match.sameContent) return "both"
  if (match.identicalToCurrent || (match.sameContent && !match.sameName))
    return "skip"
  if (match.sameName && match.canAddVersion) return "version"
  return "both"
}

/** Why it's a duplicate, in words. */
export function matchReason(match: DuplicateMatchDto): string {
  const where = `"${match.title}"${match.folderName ? ` in ${match.folderName}` : ""}`
  if (match.similarity !== null && !match.sameContent)
    return `Its text is ${match.similarity}% the same as ${where}.`
  if (match.identicalToCurrent)
    return `This exact file is already here as ${where}.`
  if (match.sameContent && match.sameName)
    return `The same file is already ${where} (version ${match.matchedVersion}).`
  if (match.sameContent)
    return `The same content is already in ${where} (version ${match.matchedVersion}).`
  return `A file with this name is already in ${where}.`
}
