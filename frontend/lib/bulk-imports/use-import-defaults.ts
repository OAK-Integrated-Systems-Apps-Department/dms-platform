"use client"

import { useQuery } from "@tanstack/react-query"

import { fetchImportDefaults } from "./api"

/**
 * The default metadata an import into `documentTypeId` would apply, and the
 * mandatory fields it would leave empty. Keyed outside `["bulk-imports"]` so
 * batch mutations, which invalidate that prefix, don't refetch it. Disabled
 * while no type is chosen.
 */
export function useImportDefaults(documentTypeId: string | null) {
  return useQuery({
    queryKey: ["import-defaults", documentTypeId],
    queryFn: () => fetchImportDefaults(documentTypeId as string),
    retry: false,
    enabled: Boolean(documentTypeId),
  })
}
