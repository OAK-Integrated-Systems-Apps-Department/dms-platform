"use client"

import { useQuery } from "@tanstack/react-query"

import { buildFolderTree, fetchFolders, flattenFolderTree } from "./api"

export const FOLDERS_KEY = ["folders"] as const

/** The tenant's folder tree, nested and flattened (for pickers). */
export function useFolders(enabled = true) {
  const query = useQuery({
    queryKey: FOLDERS_KEY,
    queryFn: fetchFolders,
    enabled,
  })
  const { roots, byId } = buildFolderTree(query.data ?? [])
  return { ...query, roots, byId, flat: flattenFolderTree(roots) }
}
