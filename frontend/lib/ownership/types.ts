/** Mirrors backend/dms-core's ownership DTOs (com.oak.dms.ownership.dto). */

/** active | locked | disabled | deactivated, or null when the account no longer exists. */
export interface PersonRefDto {
  userId: string
  name: string
  status: string | null
}

export interface DepartmentRefDto {
  departmentId: string
  name: string
}

export type OwnershipState = "assigned" | "holding"

/** Who owns a document now (the document page's Owner field). `canTransfer`: the caller may move it. */
export interface DocumentOwnershipInfoDto {
  documentId: string
  owner: PersonRefDto | null
  ownerDepartment: DepartmentRefDto | null
  ownershipState: OwnershipState
  canTransfer: boolean
}

export interface TransferDocumentRequestDto {
  newOwnerUserId: string
  newOwnerDepartmentId?: string | null
  grantAccess?: boolean
  note?: string
}

export interface TransferDocumentResponseDto {
  transferId: string
  batchId: string
  documentId: string
  previousOwner: PersonRefDto | null
  newOwner: PersonRefDto | null
  newOwnerDepartment: DepartmentRefDto | null
  accessGranted: boolean
  accessConvertedToRestricted: boolean
  newOwnerLacksViewPermission: boolean
}

export interface TransferPreviewDto {
  documentId: string
  currentOwner: PersonRefDto | null
  newOwner: PersonRefDto | null
  newOwnerDepartment: DepartmentRefDto | null
  alreadyOwner: boolean
  newOwnerCanOpenAlready: boolean
  willAddAccessEntry: boolean
  /** The new owner couldn't otherwise open it and it follows a restricted folder, so it would get its own list. */
  willConvertToRestricted: boolean
  newOwnerLacksViewPermission: boolean
}

export interface OwnedDocumentItemDto {
  documentId: string
  title: string
  documentTypeName: string | null
  folderId: string | null
  folderName: string | null
  inRecycleBin: boolean
  template: boolean
  ownershipState: OwnershipState
  owner: PersonRefDto
  ownerDepartment: DepartmentRefDto | null
  /** Pending list only: holding | owner_deactivated. */
  pendingReason: "holding" | "owner_deactivated" | null
  createdAt: string
}

/** `hiddenCount`: more documents the owner holds that the caller can't open (only an admin can transfer them). */
export interface OwnedDocumentsDto {
  items: OwnedDocumentItemDto[]
  page: number
  size: number
  total: number
  totalOwned: number
  visibleCount: number
  hiddenCount: number
}

export interface OwnedDocumentsFilters {
  userId?: string
  departmentId?: string
  q?: string
  state?: OwnershipState | ""
  /** undefined = both, true = only the Recycle Bin, false = live documents only. */
  recycleBin?: boolean
}

export type TransferMode = "all" | "selected"

export interface BulkTransferTargetDto {
  type: "user" | "holding"
  newOwnerUserId?: string | null
  newOwnerDepartmentId?: string | null
  /** Holding only: the department whose holding chain is used. */
  departmentId?: string | null
}

export interface BulkTransferRequestDto {
  sourceUserId?: string
  sourceDepartmentId?: string
  mode: TransferMode
  documentIds?: string[]
  excludeDocumentIds?: string[]
  /** At most 5,000: lowest ids first, so "all" can be repeated until nothing is left. */
  limit?: number
  target: BulkTransferTargetDto
  reason?: "manual" | "reorganization"
  grantAccess?: boolean
  note?: string
}

export interface SkippedDocumentsDto {
  reason: "not_found" | "purged" | "already_owner" | "out_of_scope"
  count: number
  documentIds: string[]
}

export interface BatchSummaryDto {
  batchId: string
  kind: string
  reason: string
  totalCount: number
  transferredCount: number
  skippedCount: number
  skipped: SkippedDocumentsDto[]
  accessGrantedCount: number
  convertedToRestrictedCount: number
  flaggedTaskCount: number
  newOwnerLacksViewPermission: boolean
  newOwner: PersonRefDto | null
  newOwnerDepartment: DepartmentRefDto | null
  holding: boolean
  createdBy: PersonRefDto | null
  createdAt: string
  completedAt: string | null
}

export interface BulkPreviewDto {
  totalCount: number
  transferableCount: number
  skipped: SkippedDocumentsDto[]
  willConvertToRestrictedCount: number
  willAddAccessEntryCount: number
  newOwnerLacksViewPermission: boolean
  newOwner: PersonRefDto | null
  newOwnerDepartment: DepartmentRefDto | null
  holding: boolean
}

export interface OpenTaskDto {
  taskId: string
  instanceId: string
  documentId: string | null
  documentTitle: string | null
  stageName: string | null
  status: string
  route: "assigned" | "claimed" | "delegated" | "escalated"
  dueAt: string | null
  instanceUrl: string
  documentUrl: string | null
  canReassign: boolean
}

export interface StartedRunDto {
  instanceId: string
  documentId: string | null
  documentTitle: string | null
  startedAt: string
  instanceUrl: string
  canCancel: boolean
}

/** In-flight approval work of a departing user: flagged, never reassigned automatically. */
export interface WorkFlagsDto {
  openTasks: OpenTaskDto[]
  startedRuns: StartedRunDto[]
  stuckTaskCount: number
  startedRunCount: number
}

export interface OffboardingPreviewDto {
  user: PersonRefDto | null
  department: DepartmentRefDto | null
  ownedTotal: number
  visibleCount: number
  hiddenCount: number
  inRecycleBinCount: number
  suggestedTarget: {
    userId: string
    name: string
    source: "holding_owner" | "department_head" | "tenant_admin"
    department: DepartmentRefDto | null
  } | null
  flags: WorkFlagsDto
  canOffboard: boolean
  blockers: string[]
}

export interface OffboardRequestDto {
  reason: string
  mode: "transfer" | "holding"
  newOwnerUserId?: string | null
  newOwnerDepartmentId?: string | null
  grantAccess?: boolean
  acknowledgedStuckTasks?: number
  note?: string
}

export interface OffboardResponseDto {
  batch: BatchSummaryDto
  flags: WorkFlagsDto
  deactivated: boolean
}

export interface OwnershipHistoryItemDto {
  transferId: string
  transferredAt: string
  reason: "manual" | "offboarding" | "reorganization" | "holding"
  batchId: string | null
  note: string | null
  accessGranted: boolean
  previousOwner: PersonRefDto | null
  previousDepartment: DepartmentRefDto | null
  newOwner: PersonRefDto | null
  newDepartment: DepartmentRefDto | null
  actor: PersonRefDto | null
}

export interface OwnershipHistoryPageDto {
  items: OwnershipHistoryItemDto[]
  page: number
  size: number
  total: number
}

export interface HistoryExportFilters {
  from?: string
  to?: string
  documentId?: string
  userId?: string
  batchId?: string
  reason?: string
}
