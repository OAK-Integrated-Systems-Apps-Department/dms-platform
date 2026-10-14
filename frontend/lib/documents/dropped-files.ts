/** A file dragged in from the computer, and the folders it sat in inside what was dropped. */
export interface DroppedFile {
  file: File
  /** e.g. ["Reports", "2026"] for Reports/2026/q1.pdf when the Reports folder was dropped; [] for a loose file. */
  dir: string[]
}

/** System clutter that comes along when a whole folder is dropped. */
const IGNORED = new Set([".ds_store", "thumbs.db", "desktop.ini"])

/** True while files (not text or a link) are being dragged in from outside the page. */
export function isFileDrag(dataTransfer: DataTransfer | null): boolean {
  return Boolean(
    dataTransfer && Array.from(dataTransfer.types).includes("Files")
  )
}

/**
 * Everything in a drop - loose files, and the files inside any folders,
 * however deep, with the folder path they were in. Must be called inside the
 * drop handler itself: the browser empties the DataTransfer once it returns,
 * so the entries are taken synchronously and only read afterwards.
 */
export function readDrop(dataTransfer: DataTransfer): Promise<DroppedFile[]> {
  const entries = Array.from(dataTransfer.items)
    .filter((item) => item.kind === "file")
    .map((item) => item.webkitGetAsEntry?.() ?? null)
  if (entries.length === 0 || entries.some((entry) => entry === null)) {
    // No entry API (or something it can't describe): loose files only.
    const files = Array.from(dataTransfer.files).map((file) => ({
      file,
      dir: [],
    }))
    return Promise.resolve(files)
  }
  return collect(entries as FileSystemEntry[], [])
}

async function collect(
  entries: FileSystemEntry[],
  dir: string[]
): Promise<DroppedFile[]> {
  const found: DroppedFile[] = []
  for (const entry of entries) {
    if (IGNORED.has(entry.name.toLowerCase())) continue
    if (entry.isFile) {
      found.push({ file: await fileOf(entry as FileSystemFileEntry), dir })
    } else if (entry.isDirectory) {
      const children = await childrenOf(entry as FileSystemDirectoryEntry)
      found.push(...(await collect(children, [...dir, entry.name])))
    }
  }
  return found
}

function fileOf(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

/** readEntries hands a directory over in batches (100 at a time in Chrome) - read until it's done. */
async function childrenOf(
  directory: FileSystemDirectoryEntry
): Promise<FileSystemEntry[]> {
  const reader = directory.createReader()
  const all: FileSystemEntry[] = []
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) =>
      reader.readEntries(resolve, reject)
    )
    if (batch.length === 0) return all
    all.push(...batch)
  }
}

/**
 * The files of a folder chosen with the "Upload folder" picker
 * (`<input webkitdirectory>`), with the folder path each was in - the
 * picked folder itself included, as when it's dragged in.
 */
export function fromFolderPicker(files: FileList | File[]): DroppedFile[] {
  return Array.from(files)
    .filter((file) => !IGNORED.has(file.name.toLowerCase()))
    .map((file) => ({
      file,
      dir: (file.webkitRelativePath || file.name).split("/").slice(0, -1),
    }))
}
