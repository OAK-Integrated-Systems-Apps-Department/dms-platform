/**
 * Saves `blob` as a file named `filename` through a temporary link - the
 * browser's own download, so it lands wherever the user's downloads go.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/**
 * The filename from a Content-Disposition header: the RFC 5987
 * `filename*=UTF-8''...` form when present (it can carry non-ASCII names),
 * otherwise `filename="..."` or a bare `filename=...`. Null when there is
 * neither.
 */
export function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null

  const extended = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[2].trim().replace(/^"|"$/g, ""))
    } catch {
      // Malformed percent-encoding - fall back to the plain parameter.
    }
  }

  const quoted = /filename\s*=\s*"([^"]*)"/i.exec(header)
  if (quoted) return quoted[1] || null
  const bare = /filename\s*=\s*([^;\s]+)/i.exec(header)
  return bare ? bare[1] : null
}
