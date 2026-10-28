"use client"

import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { fetchDocumentReview, fetchPageImage, type PageImage } from "@/lib/documents/api"

export function useDocumentReview(documentId: string) {
  return useQuery({
    queryKey: ["document-review", documentId],
    queryFn: () => fetchDocumentReview(documentId),
    enabled: Boolean(documentId),
  })
}

/** What a settled fetch produced, tagged with the request it answered. */
interface Settled {
  key: string
  image: PageImage | null
  error: Error | null
}

/**
 * The current page's rendered image, as an object URL.
 *
 * Not `useQuery`, because an object URL is a resource the browser holds
 * until it is explicitly revoked -- a cache of them is a memory leak that
 * grows with every page the reviewer looks at. This owns exactly one at a
 * time and revokes the previous one on every change, including unmount.
 *
 * `isLoading` is DERIVED from whether the settled result answers the
 * request currently being asked for, rather than set at the top of the
 * effect. Resetting state synchronously in an effect body triggers a
 * cascading render on every page change -- and the derived form is also
 * simply more correct: there is exactly one source of truth for "have we
 * got this page yet", so the two can never disagree.
 *
 * `generation` lets a caller force a re-fetch (after a re-run, say)
 * without changing the page number.
 */
export function usePageImage(documentId: string, page: number, generation = 0) {
  const key = `${documentId}:${page}:${generation}`
  const [settled, setSettled] = useState<Settled | null>(null)

  useEffect(() => {
    let cancelled = false
    let url: string | null = null

    fetchPageImage(documentId, page)
      .then((result) => {
        if (cancelled) {
          // The page changed while this was in flight. Revoke it here
          // rather than letting it leak: nothing else has a reference.
          URL.revokeObjectURL(result.url)
          return
        }
        url = result.url
        setSettled({ key, image: result, error: null })
      })
      .catch((error: Error) => {
        if (cancelled) return
        setSettled({ key, image: null, error })
      })

    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [documentId, page, generation, key])

  const current = settled?.key === key ? settled : null
  return {
    image: current?.image ?? null,
    error: current?.error ?? null,
    isLoading: current === null,
  }
}
