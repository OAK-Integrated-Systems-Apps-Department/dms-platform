"use client"

import { useEffect, useMemo, useRef, useState, type RefObject } from "react"
import { Download, Loader2, Mail, Paperclip } from "lucide-react"

import { Button } from "@/components/ui/button"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/*
 * Viewers for files the browser can't show by itself, parsed right here in
 * the page (each library is loaded only when such a file is opened):
 * spreadsheets (SheetJS), email (postal-mime for .eml, msgreader for
 * Outlook .msg), HEIC photos (heic2any) and TIFF (UTIF). Every file reaching
 * these was scanned clean before it was stored.
 */

const MAX_ROWS = 2000
const MAX_COLUMNS = 60
const MAX_TIFF_PAGES = 30

function Working({ label }: { label: string }) {
  return (
    <p className="m-auto inline-flex items-center gap-2 text-[13px] text-muted-foreground">
      <Loader2 size={16} className="animate-spin" /> {label}
    </p>
  )
}

/** Runs a parse once per file; null while it runs. A failure calls onBroken. */
function useParsed<T>(
  parse: () => Promise<T>,
  deps: unknown[],
  onBroken: () => void
): T | null {
  const [value, setValue] = useState<T | null>(null)
  useEffect(() => {
    let cancelled = false
    parse()
      .then((result) => {
        if (!cancelled) setValue(result)
      })
      .catch(() => {
        if (!cancelled) onBroken()
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return value
}

// ------------------------------------------------------------ spreadsheets

interface Sheet {
  name: string
  rows: string[][]
  truncated: boolean
}

function columnName(index: number): string {
  let name = ""
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26))
    name = String.fromCharCode(65 + ((n - 1) % 26)) + name
  return name
}

/** Excel, OpenDocument spreadsheets, CSV and TSV - one tab per sheet, like Excel. */
export function SheetViewer({
  blob,
  extension,
  zoom,
  onBroken,
}: {
  blob: Blob
  extension: string
  zoom: number
  onBroken: () => void
}) {
  const sheets = useParsed(
    async () => {
      const XLSX = await import("xlsx")
      const delimited = extension === "csv" || extension === "tsv"
      const workbook = delimited
        ? XLSX.read(await blob.text(), {
            type: "string",
            FS: extension === "tsv" ? "\t" : ",",
            sheetRows: MAX_ROWS + 1,
            raw: true,
          })
        : XLSX.read(await blob.arrayBuffer(), {
            type: "array",
            cellDates: true,
            sheetRows: MAX_ROWS + 1,
          })
      return workbook.SheetNames.map((name): Sheet => {
        const rows = XLSX.utils.sheet_to_json<unknown[]>(
          workbook.Sheets[name],
          {
            header: 1,
            raw: false,
            blankrows: false,
            defval: "",
          }
        )
        return {
          name,
          rows: rows
            .slice(0, MAX_ROWS)
            .map((row) =>
              row.slice(0, MAX_COLUMNS).map((cell) => String(cell ?? ""))
            ),
          truncated: rows.length > MAX_ROWS,
        }
      })
    },
    [blob, extension],
    onBroken
  )
  const [active, setActive] = useState(0)
  if (!sheets) return <Working label="Reading the spreadsheet…" />
  const sheet = sheets[Math.min(active, sheets.length - 1)]
  const width = Math.max(1, ...sheet.rows.map((row) => row.length))

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-auto bg-card">
        {sheet.rows.length === 0 ? (
          <p className="p-6 text-[13px] text-muted-foreground">
            This sheet is empty.
          </p>
        ) : (
          <table className="border-collapse text-[12.5px]" style={{ zoom }}>
            <thead className="sticky top-0 z-10">
              <tr>
                <th className="sticky left-0 z-20 min-w-10 border border-border bg-muted px-2 py-1" />
                {Array.from({ length: width }, (_, i) => (
                  <th
                    key={i}
                    className="min-w-24 border border-border bg-muted px-2 py-1 text-center font-medium text-muted-foreground"
                  >
                    {columnName(i)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sheet.rows.map((row, r) => (
                <tr key={r}>
                  <th className="sticky left-0 border border-border bg-muted px-2 py-1 text-right font-medium text-muted-foreground tabular-nums">
                    {r + 1}
                  </th>
                  {Array.from({ length: width }, (_, c) => (
                    <td
                      key={c}
                      className="max-w-[320px] truncate border border-border px-2 py-1 whitespace-nowrap"
                      title={row[c] ?? ""}
                    >
                      {row[c] ?? ""}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {sheet.truncated && (
          <p className="p-3 text-[12px] text-muted-foreground">
            Showing the first {MAX_ROWS.toLocaleString()} rows - download the
            file to see the rest.
          </p>
        )}
      </div>
      {sheets.length > 1 && (
        <div className="flex shrink-0 gap-1 overflow-x-auto border-t border-border bg-muted/40 px-2 py-1.5">
          {sheets.map((s, i) => (
            <button
              key={s.name + i}
              type="button"
              onClick={() => setActive(i)}
              className={cn(
                "rounded-md px-3 py-1 text-[12.5px] whitespace-nowrap",
                i === active
                  ? "bg-card font-semibold text-foreground shadow-sm"
                  : "text-muted-foreground hover:bg-card/60"
              )}
            >
              {s.name}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ------------------------------------------------------------ email

interface Attachment {
  name: string
  size: number
  content: () => Promise<Uint8Array>
}

interface Email {
  subject: string
  from: string
  to: string
  cc: string
  date: string | null
  html: string | null
  text: string | null
  attachments: Attachment[]
}

function people(
  list: { name?: string; address?: string }[] | undefined
): string {
  return (list ?? [])
    .map((p) =>
      p.name && p.address
        ? `${p.name} <${p.address}>`
        : (p.address ?? p.name ?? "")
    )
    .filter(Boolean)
    .join(", ")
}

async function parseEml(blob: Blob): Promise<Email> {
  const { default: PostalMime } = await import("postal-mime")
  const mail = await PostalMime.parse(await blob.arrayBuffer())
  return {
    subject: mail.subject ?? "(no subject)",
    from: people(mail.from ? [mail.from] : []),
    to: people(mail.to),
    cc: people(mail.cc),
    date: mail.date ?? null,
    html: mail.html ?? null,
    text: mail.text ?? null,
    attachments: (mail.attachments ?? []).map((a) => {
      const bytes =
        typeof a.content === "string"
          ? new TextEncoder().encode(a.content)
          : new Uint8Array(a.content)
      return {
        name: a.filename ?? "attachment",
        size: bytes.byteLength,
        content: async () => bytes,
      }
    }),
  }
}

async function parseMsg(blob: Blob): Promise<Email> {
  const { default: MsgReader } = await import("@kenjiuno/msgreader")
  const reader = new MsgReader(await blob.arrayBuffer())
  const data = reader.getFileData()
  const recipients = (type: string) =>
    (data.recipients ?? [])
      .filter((r) => (r.recipType ?? "to") === type)
      .map((r) =>
        r.name && r.email ? `${r.name} <${r.email}>` : (r.email ?? r.name ?? "")
      )
      .filter(Boolean)
      .join(", ")
  const html =
    data.bodyHtml ?? (data.html ? new TextDecoder().decode(data.html) : null)
  return {
    subject: data.subject ?? "(no subject)",
    from:
      data.senderName && data.senderEmail
        ? `${data.senderName} <${data.senderEmail}>`
        : (data.senderEmail ?? data.senderName ?? ""),
    to: recipients("to"),
    cc: recipients("cc"),
    date: data.messageDeliveryTime ?? data.clientSubmitTime ?? null,
    html,
    text: data.body ?? null,
    attachments: (data.attachments ?? []).map((a) => ({
      name: a.fileName ?? a.fileNameShort ?? "attachment",
      size: a.contentLength ?? 0,
      content: async () => reader.getAttachment(a).content,
    })),
  }
}

/**
 * The email's HTML, in a sandboxed frame: no scripts, no access to this page,
 * and nothing loaded from the internet (no tracking pixels) - only images
 * embedded in the email itself.
 */
function htmlDocument(html: string, zoom: number): string {
  const policy =
    "default-src 'none'; img-src data: cid:; style-src 'unsafe-inline'; font-src data:"
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${policy}"><base target="_blank"><style>html{zoom:${zoom}}body{font-family:system-ui,sans-serif;font-size:14px;line-height:1.5;color:#1a1a1a;background:#fff;margin:16px;word-wrap:break-word}img{max-width:100%;height:auto}</style></head><body>${html}</body></html>`
}

/** An email (.eml or Outlook .msg): who, when, the message itself, and its attachments. */
export function EmailViewer({
  blob,
  extension,
  zoom,
  onBroken,
}: {
  blob: Blob
  extension: string
  zoom: number
  onBroken: () => void
}) {
  const email = useParsed(
    () => (extension === "msg" ? parseMsg(blob) : parseEml(blob)),
    [blob, extension],
    onBroken
  )
  const frame = useMemo(
    () => (email?.html ? htmlDocument(email.html, zoom) : null),
    [email, zoom]
  )
  if (!email) return <Working label="Reading the email…" />

  async function save(attachment: Attachment) {
    const bytes = await attachment.content()
    const url = URL.createObjectURL(new Blob([bytes as BlobPart]))
    const link = document.createElement("a")
    link.href = url
    link.download = attachment.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-card">
      <div className="shrink-0 border-b border-border px-5 py-4">
        <h3 className="flex items-start gap-2 text-[15px] font-semibold">
          <Mail size={16} className="mt-0.5 shrink-0 text-primary" />
          {email.subject}
        </h3>
        <dl className="mt-2 grid grid-cols-[52px_1fr] gap-x-2 gap-y-0.5 text-[12.5px]">
          {[
            ["From", email.from],
            ["To", email.to],
            ["Cc", email.cc],
            ["Date", email.date ? new Date(email.date).toLocaleString() : ""],
          ]
            .filter(([, value]) => value)
            .map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-muted-foreground">{label}</dt>
                <dd className="min-w-0 break-words">{value}</dd>
              </div>
            ))}
        </dl>
        {email.attachments.length > 0 && (
          <div className="mt-3 flex flex-wrap gap-2">
            {email.attachments.map((a, i) => (
              <Button
                key={a.name + i}
                variant="outline"
                size="sm"
                onClick={() => void save(a)}
                title="Download this attachment"
              >
                <Paperclip />
                <span className="max-w-[220px] truncate">{a.name}</span>
                <span className="text-muted-foreground">
                  {formatBytes(a.size)}
                </span>
                <Download />
              </Button>
            ))}
          </div>
        )}
      </div>
      {frame ? (
        <iframe
          title={email.subject}
          sandbox=""
          srcDoc={frame}
          className="min-h-0 w-full flex-1 border-0 bg-white"
        />
      ) : (
        <pre
          className="min-h-0 flex-1 overflow-auto p-5 font-sans leading-[1.6] whitespace-pre-wrap"
          style={{ fontSize: 13.5 * zoom }}
        >
          {email.text || "(This email has no text.)"}
        </pre>
      )}
    </div>
  )
}

// ------------------------------------------------------------ images

/** HEIC/HEIF photos (iPhone) - converted to JPEG here in the page. */
export function HeicViewer({
  blob,
  title,
  zoom,
  onBroken,
}: {
  blob: Blob
  title: string
  zoom: number
  onBroken: () => void
}) {
  const url = useParsed(
    async () => {
      const { default: heic2any } = await import("heic2any")
      const converted = await heic2any({
        blob,
        toType: "image/jpeg",
        quality: 0.9,
      })
      return URL.createObjectURL(
        Array.isArray(converted) ? converted[0] : converted
      )
    },
    [blob],
    onBroken
  )
  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url)
    },
    [url]
  )
  if (!url) return <Working label="Opening the photo…" />
  return <ZoomableImage src={url} alt={title} zoom={zoom} />
}

/** TIFF images - every page (scanned documents often have several). */
export function TiffViewer({
  blob,
  title,
  zoom,
  onBroken,
}: {
  blob: Blob
  title: string
  zoom: number
  onBroken: () => void
}) {
  const [frame, box] = useBoxSize()
  const pages = useParsed(
    async () => {
      const UTIF = (await import("utif2")).default
      const buffer = await blob.arrayBuffer()
      const ifds = UTIF.decode(buffer).slice(0, MAX_TIFF_PAGES)
      return ifds.map((ifd) => {
        UTIF.decodeImage(buffer, ifd)
        const rgba = UTIF.toRGBA8(ifd)
        const canvas = document.createElement("canvas")
        canvas.width = ifd.width
        canvas.height = ifd.height
        const context = canvas.getContext("2d")
        if (!context) throw new Error("no canvas")
        context.putImageData(
          new ImageData(
            new Uint8ClampedArray(rgba.buffer as ArrayBuffer),
            ifd.width,
            ifd.height
          ),
          0,
          0
        )
        return { src: canvas.toDataURL("image/png"), width: ifd.width }
      })
    },
    [blob],
    onBroken
  )
  return (
    <div
      ref={frame}
      className="flex min-h-0 flex-1 flex-col gap-4 overflow-auto p-6"
    >
      {!pages ? (
        <Working label="Opening the image…" />
      ) : (
        pages.map((page, i) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            key={i}
            src={page.src}
            alt={`${title} - page ${i + 1}`}
            style={{
              width: Math.max(
                1,
                Math.round(Math.min(page.width, box.width || page.width) * zoom)
              ),
              maxWidth: "none",
              height: "auto",
            }}
            className="mx-auto shrink-0 rounded-md bg-white shadow-[var(--ledger-shadow)]"
          />
        ))
      )}
    </div>
  )
}

/** The inner size of an element, kept up to date as the panel is resized. */
function useBoxSize(): [
  RefObject<HTMLDivElement | null>,
  { width: number; height: number },
] {
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) =>
      setSize({
        width: entry.contentRect.width,
        height: entry.contentRect.height,
      })
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return [ref, size]
}

/**
 * An image at a zoom level: 1 = fitted to the panel (never enlarged past its
 * own size), 2 = twice that, and so on - scaled from the image's real
 * pixels, so zooming in shows real detail. Scrolls when larger than the panel.
 */
export function ZoomableImage({
  src,
  alt,
  zoom,
  onError,
}: {
  src: string
  alt: string
  zoom: number
  onError?: () => void
}) {
  const [frame, box] = useBoxSize()
  const [natural, setNatural] = useState<{
    width: number
    height: number
  } | null>(null)
  const fit =
    natural && box.width > 0 && box.height > 0
      ? Math.min(1, box.width / natural.width, box.height / natural.height)
      : 1
  const width = natural
    ? Math.max(1, Math.round(natural.width * fit * zoom))
    : undefined
  return (
    <div ref={frame} className="flex min-h-0 flex-1 overflow-auto p-6">
      {/* A blob URL of the user's own file - next/image can't optimise it. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={src}
        alt={alt}
        onError={onError}
        onLoad={(event) =>
          setNatural({
            width: event.currentTarget.naturalWidth,
            height: event.currentTarget.naturalHeight,
          })
        }
        style={
          width
            ? { width, maxWidth: "none", height: "auto" }
            : { maxWidth: "100%", maxHeight: "100%" }
        }
        className="m-auto shrink-0 rounded-md bg-white shadow-[var(--ledger-shadow)]"
      />
    </div>
  )
}
