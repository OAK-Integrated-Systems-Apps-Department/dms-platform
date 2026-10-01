"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchSelectableDocumentTypes } from "./api"

export function useSelectableDocumentTypes(enabled: boolean) {
  return useQuery({
    queryKey: ["document-types", "selectable"],
    queryFn: fetchSelectableDocumentTypes,
    enabled,
    staleTime: 5 * 60 * 1000,
  })
}
