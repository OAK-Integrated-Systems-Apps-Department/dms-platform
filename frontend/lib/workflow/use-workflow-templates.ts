"use client"

import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { fetchWorkflowTemplates } from "./api"
import type { WorkflowTemplateFilters } from "./api"

/**
 * Server-driven list of template versions, like `useQuarantineRecords`:
 * every filter/page/size change re-queries `GET /api/v1/workflow/templates`.
 * `page` is 1-indexed (matching `TablePagination`) and converted to the
 * backend's 0-indexed page here.
 */
export function useWorkflowTemplates(filters: WorkflowTemplateFilters, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ["workflow", "templates", filters, page, size],
    queryFn: () => fetchWorkflowTemplates(filters, page - 1, size),
    placeholderData: keepPreviousData,
    enabled,
  })
}
