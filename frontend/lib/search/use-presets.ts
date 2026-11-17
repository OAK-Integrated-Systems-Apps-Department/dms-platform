"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { createPreset, deletePreset, fetchPresets, runPreset, updatePreset } from "./presets-api"
import type { PresetCriteria } from "./presets"

export const PRESETS_KEY = ["search", "presets"] as const

/** The caller's saved presets (names and one-line summaries; criteria are fetched when run). */
export function usePresets(enabled = true) {
  return useQuery({
    queryKey: PRESETS_KEY,
    queryFn: fetchPresets,
    enabled,
    staleTime: 30_000,
    retry: false,
  })
}

export function usePresetMutations() {
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey: PRESETS_KEY })
  return {
    create: useMutation({
      mutationFn: (input: { name: string; criteria: PresetCriteria; overwrite?: boolean }) => createPreset(input),
      onSettled: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, ...input }: { id: string; name?: string; criteria?: PresetCriteria }) =>
        updatePreset(id, input),
      onSettled: refresh,
    }),
    remove: useMutation({ mutationFn: (id: string) => deletePreset(id), onSettled: refresh }),
    run: useMutation({ mutationFn: (id: string) => runPreset(id) }),
    refresh,
  }
}
