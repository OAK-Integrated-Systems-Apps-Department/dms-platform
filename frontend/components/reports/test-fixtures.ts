import type { CatalogDto, RunDto, ScheduleDto, TemplateDto } from "@/lib/reports/api"

/** Small builders shared by the user-area component tests. */
export function catalogFixture(over: { settings?: Partial<CatalogDto["settings"]>; capabilities?: Partial<CatalogDto["capabilities"]> } = {}): CatalogDto {
  return {
    fields: [
      { key: "title", label: "Title", type: "text", operators: ["contains", "equals"], multi: false, metadata: false, selectable: true, filterable: true, sortable: true, groupable: false, defaultSelected: true },
      { key: "status", label: "Status", type: "choice", operators: ["is_any_of"], multi: true, metadata: false, selectable: true, filterable: true, sortable: true, groupable: true, defaultSelected: true },
      { key: "createdAt", label: "Created", type: "date", operators: ["relative", "between"], multi: false, metadata: false, selectable: true, filterable: true, sortable: true, groupable: false, defaultSelected: false },
    ],
    settings: {
      allowedFormats: ["csv", "xlsx", "pdf"],
      defaultFormat: "csv",
      overLimitBehaviour: "refuse",
      defaultTimeZone: "Asia/Colombo",
      includeInactiveByDefault: false,
      defaultColumns: ["title"],
      defaultSort: [],
      limits: { maxFilters: 25, maxColumns: 40, maxMetadataFilters: 5, maxSortKeys: 3, maxRows: 100000, maxPdfRows: 5000 },
      schedulingEnabled: true,
      minScheduleInterval: "daily",
      allowedDeliveryChannels: ["in_app", "email"],
      deliveryModeDefault: "link",
      allowAttachments: true,
      maxAttachmentMb: 10,
      allowExternalRecipients: true,
      maxRecipientsPerSchedule: 50,
      showFilterSummary: true,
      ...over.settings,
    },
    capabilities: { build: true, run: true, schedule: true, publish: false, configure: false, viewAllRuns: false, ...over.capabilities },
    relativeDateOptions: ["today", "last_7_days", "last_30_days"],
    maxRelativeDays: 366,
  }
}

export function templateFixture(over: Partial<TemplateDto> = {}): TemplateDto {
  return {
    id: "t1",
    name: "Contracts",
    description: null,
    scope: "private",
    ownerUserId: "u1",
    ownerName: "Ana Perera",
    definition: { source: "documents", filters: [], columns: ["title"], sort: [], groupBy: null, includeInactive: false, timeZone: null },
    locked: false,
    userEditableFilters: null,
    access: "owner",
    warnings: null,
    grants: null,
    activeSchedules: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: null,
    ...over,
  }
}

export function runFixture(over: Partial<RunDto> = {}): RunDto {
  return {
    runId: "r1",
    status: "completed",
    format: "csv",
    trigger: "manual",
    templateId: "t1",
    title: "Contracts",
    rowCount: 12,
    truncated: false,
    progressRows: 12,
    errorCode: null,
    errorMessage: null,
    createdAt: "2026-03-10T06:30:00Z",
    startedAt: "2026-03-10T06:30:01Z",
    finishedAt: "2026-03-10T06:30:05Z",
    expiresAt: "2026-03-17T06:30:00Z",
    downloadable: true,
    ...over,
  }
}

export function scheduleFixture(over: Partial<ScheduleDto> = {}): ScheduleDto {
  return {
    id: "s1",
    templateId: "t1",
    templateName: "Contracts",
    ownerUserId: "u1",
    name: "Weekly contracts",
    frequency: "weekly",
    daysOfWeek: [1],
    dayOfMonth: null,
    timeOfDay: "08:00",
    timeZone: "Asia/Colombo",
    startDate: null,
    endDate: null,
    format: "csv",
    deliveryChannels: ["in_app"],
    deliveryMode: "link",
    recipients: [],
    status: "active",
    pauseReason: null,
    nextRunAt: "2026-03-16T02:30:00Z",
    lastRunAt: null,
    consecutiveFailures: 0,
    lastFailureCode: null,
    createdAt: "2026-03-01T00:00:00Z",
    ...over,
  }
}
