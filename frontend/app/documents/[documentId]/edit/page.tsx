"use client"

import { use } from "react"

import { DocumentEditor } from "@/components/checkout/document-editor"

/**
 * The online editor for one document (2.10). Reached from the document page's "Edit online" button, which is
 * only offered when the editing engine is on and can edit the file. `params` is a Promise in this version of Next.
 */
export default function DocumentEditPage({
  params,
  searchParams,
}: {
  params: Promise<{ documentId: string }>
  searchParams: Promise<{ layout?: string }>
}) {
  const { documentId } = use(params)
  const { layout } = use(searchParams)
  // ?layout=full is the tab "Open full screen" opens: the editor filling the whole window.
  return <DocumentEditor documentId={documentId} fullScreen={layout === "full"} />
}
