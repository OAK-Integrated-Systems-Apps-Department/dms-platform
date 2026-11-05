"use client"

import type { ReactNode } from "react"
import { useQuery } from "@tanstack/react-query"

import { canDrawAsPages, fetchThumbnail } from "@/lib/preview/api"
import { useNearViewport } from "@/lib/use-near-viewport"

/** A picture as a data URL - nothing to free later, and thumbnails are small. */
function dataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(blob)
  })
}

/**
 * A small picture of a document's first page (made on the server when it was stored - DPRM-FR-001), fetched when
 * it is about to scroll into view and kept. While it loads, and for a file with no picture (a type that can't be
 * drawn, or one whose conversion failed), it shows {@code children} - the file-type icon - so the document's
 * details are never hidden by a missing preview (DPRM-FR-006).
 */
export function DocumentThumbnail({
  documentId,
  fileName,
  versionNumber,
  children,
}: {
  documentId: string
  fileName: string | null
  /** A new version has a new first page - so a new picture. */
  versionNumber: number | null
  children: ReactNode
}) {
  const [ref, near] = useNearViewport<HTMLSpanElement>()
  const drawable = canDrawAsPages(fileName)
  const thumbnail = useQuery({
    queryKey: ["thumbnail", documentId, versionNumber],
    queryFn: async () => {
      const blob = await fetchThumbnail(documentId)
      return blob ? await dataUrl(blob) : null
    },
    enabled: near && drawable,
    staleTime: 5 * 60_000,
    gcTime: 15 * 60_000,
    retry: false,
  })
  return (
    <span
      ref={ref}
      className="absolute inset-0 flex flex-col items-center justify-center gap-1.5"
    >
      {thumbnail.data ? (
        // eslint-disable-next-line @next/next/no-img-element -- a data URL of a server-drawn first page
        <img
          src={thumbnail.data}
          alt=""
          draggable={false}
          className="absolute inset-0 size-full object-cover object-top"
        />
      ) : null}
      {thumbnail.data ? null : children}
    </span>
  )
}
