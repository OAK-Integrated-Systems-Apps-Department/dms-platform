import type { CatalogDto, TemplateDto } from "@/lib/reports/api"
import type { ReportDefinition } from "@/lib/reports/definition"

/** A small catalogue shared by the builder tests. */
export function catalogFixture(overrides: Partial<CatalogDto["capabilities"]> = {}): CatalogDto {
  const field = (key: string, label: string, type: CatalogDto["fields"][number]["type"], operators: string[], extra = {}) => ({
    key, label, type, operators, multi: false, metadata: false, selectable: true, filterable: true, sortable: true, groupable: false, defaultSelected: false, ...extra,
  })
  return {
    fields: [
      field("title", "Title", "text", ["contains", "equals", "is_empty"], { defaultSelected: true }),
      field("status", "Status", "choice", ["is_any_of", "is_none_of"], { groupable: true }),
      field("createdAt", "Created", "date", ["between", "relative", "after"]),
      field("pageCount", "Page count", "number", ["gt", "lt"]),
      field("contractValue", "Contract value", "number", ["gt"], { metadata: true }),
    ],
    settings: {
      allowedFormats: ["csv", "xlsx", "pdf"], defaultFormat: "csv", overLimitBehaviour: "fail", defaultTimeZone: "UTC",
      includeInactiveByDefault: false, defaultColumns: ["title"], defaultSort: [],
      limits: { maxFilters: 10, maxColumns: 8, maxMetadataFilters: 3, maxSortKeys: 2, maxRows: 100, maxPdfRows: 20 },
      schedulingEnabled: true, minScheduleInterval: "daily", allowedDeliveryChannels: ["in_app"], deliveryModeDefault: "link",
      allowAttachments: false, maxAttachmentMb: 10, allowExternalRecipients: false, maxRecipientsPerSchedule: 10, showFilterSummary: true,
    },
    capabilities: { build: true, run: true, schedule: true, publish: false, configure: false, viewAllRuns: false, ...overrides },
    relativeDateOptions: ["today", "last_30_days"],
    maxRelativeDays: 365,
  }
}

export function definitionFixture(extra: Partial<ReportDefinition> = {}): ReportDefinition {
  return { source: "documents", filters: [], columns: ["title", "status"], sort: [], groupBy: null, includeInactive: false, timeZone: null, ...extra }
}

export function templateFixture(extra: Partial<TemplateDto> = {}): TemplateDto {
  return {
    id: "t1", name: "Contracts", description: null, scope: "private", ownerUserId: "u1", ownerName: "Ana",
    definition: definitionFixture(), locked: false, userEditableFilters: [], access: "owner", warnings: [], grants: [],
    activeSchedules: 0, createdAt: "2026-01-01T00:00:00Z", updatedAt: null, ...extra,
  }
}
