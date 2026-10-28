/** A file fetched from the API, ready to hand to the browser. */
export interface DownloadedFile {
  blob: Blob
  filename: string
}

/** Saves a fetched file through a temporary link, as the browser's own download. */
export function saveDownloadedFile({ blob, filename }: DownloadedFile) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
