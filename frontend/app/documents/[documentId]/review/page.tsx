"use client"

import { use } from "react"

import { DocumentReviewContent } from "@/components/documents/document-review-content"

/**
 * The review screen (Phase 4c, production prompt 3.13): page preview with
 * extracted-field highlights, each field's value/confidence/status,
 * click-to-scroll, and accept / edit / re-read-a-region.
 *
 * Reached from the Review Queue. `params` is a Promise in this version of
 * Next, unwrapped with `use()`.
 */
export default function DocumentReviewPage({
  params,
}: {
  params: Promise<{ documentId: string }>
}) {
  const { documentId } = use(params)
  return <DocumentReviewContent documentId={documentId} />
}
