/** Mirrors backend/dms-core's relationship DTOs (com.oak.dms.relationship.dto). */

export type Directionality = "directional" | "symmetric"

/** Mirrors RelationshipTypeResponse. `usageCount` is the number of active links using the type. */
export interface RelationshipTypeDto {
  relationshipTypeId: string
  code: string
  category: string
  label: string
  inverseLabel: string
  directionality: Directionality
  preventsCycles: boolean
  system: boolean
  enabled: boolean
  displayOrder: number
  usageCount: number
}

/** Mirrors CreateRelationshipTypeRequest. */
export interface CreateRelationshipTypeRequestDto {
  label: string
  inverseLabel?: string
  directionality: Directionality
  preventsCycles?: boolean
  category?: string
}

/** Mirrors UpdateRelationshipTypeRequest - only the fields sent change. */
export interface UpdateRelationshipTypeRequestDto {
  label?: string
  inverseLabel?: string
  enabled?: boolean
  displayOrder?: number
  directionality?: Directionality
  preventsCycles?: boolean
}

/** Mirrors RelationshipResponse - a link as created or changed (direction is source -> target). */
export interface RelationshipDto {
  relationshipId: string
  relationshipTypeId: string
  typeCode: string
  typeLabel: string
  inverseLabel: string
  sourceDocumentId: string
  targetDocumentId: string
  origin: string
  batchId: string | null
  note: string | null
  createdAt: string
}

export interface CreateRelationshipRequestDto {
  relationshipTypeId: string
  sourceDocumentId: string
  targetDocumentId: string
  note?: string
}

export interface BulkCreateRelationshipsRequestDto {
  relationshipTypeId: string
  sourceDocumentIds: string[]
  targetDocumentIds: string[]
  note?: string
}

export interface BulkRelationshipsResponseDto {
  batchId: string
  created: RelationshipDto[]
}

/** One reason a bulk request was refused (error `details`). */
export interface BulkErrorDto {
  documentId: string
  code: string
  message: string
}

export interface UpdateRelationshipRequestDto {
  relationshipTypeId?: string
  note?: string
}

/** One document in a loop chain (error `details.chain`); a hidden one has no id. */
export interface ChainItemDto {
  documentId?: string
  title: string
}

export type RelationshipDirection = "outgoing" | "incoming" | "symmetric"

/** The other end of a link; a document the caller can't open is only `{ accessible: false }`. */
export interface RelatedOtherDto {
  accessible: boolean
  documentId?: string
  title?: string
  documentTypeName?: string
  status?: string
  inRecycleBin?: boolean
  folderId?: string
}

export interface RelatedItemDto {
  relationshipId: string
  direction: RelationshipDirection
  label: string
  origin: string
  note: string | null
  createdAt: string
  createdBy: { userId: string; name: string } | null
  canEdit: boolean
  canRemove: boolean
  other: RelatedOtherDto
}

export interface RelatedGroupDto {
  relationshipTypeId: string
  typeCode: string
  typeLabel: string
  typeInverseLabel: string
  typeEnabled: boolean
  items: RelatedItemDto[]
}

/** Mirrors RelatedDocumentsResponse - the Related Documents panel of one document. */
export interface RelatedDocumentsDto {
  documentId: string
  total: number
  canAdd: boolean
  groups: RelatedGroupDto[]
}

export interface RelationshipCountsDto {
  counts: Record<string, number>
}
