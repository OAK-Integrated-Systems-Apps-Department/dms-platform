"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchDocument } from "./api"

/** One document's metadata, for the document page. */
export function useDocument(documentId: string) {
  return useQuery({
    queryKey: ["document", documentId],
    queryFn: () => fetchDocument(documentId),
    enabled: Boolean(documentId),
    retry: false,
  })
}
