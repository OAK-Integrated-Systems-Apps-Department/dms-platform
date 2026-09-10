/**
 * A single grantable permission in the catalog (mirrors
 * `PermissionResponse.java`). Hybrid tenant-scoping: `shared` permissions
 * are the platform's default catalog, visible to and grantable by every
 * tenant; non-shared ones were created by one specific tenant through
 * these screens, visible only to that tenant, and editable/deletable only
 * by them — a caller only ever sees their own tenant's custom permissions
 * here, never another tenant's, so if `shared` is false it's always the
 * current tenant's own.
 */
export interface PermissionRecord {
  id: string
  /** Stable machine key checked by `@authz.hasPermission(...)` throughout the backend, e.g. "document.approve". Immutable after creation. */
  code: string
  name: string
  description: string
  /** True for the platform's default catalog (read-only from every tenant's Permission Catalog screens); false for a permission this tenant created themselves (editable/deletable by them). */
  shared: boolean
  actionId: string
  /** Denormalized from the related action, so the table can show "MODULE / ACTION" without a second lookup. */
  actionCode: string
  moduleId: string
  moduleCode: string
}

/** A verb available under a module (mirrors `PermissionActionResponse.java`). */
export interface PermissionActionRecord {
  id: string
  moduleId: string
  moduleCode: string
  code: string
  name: string
  description: string
}

/** A top-level grouping in the catalog (mirrors `PermissionModuleResponse.java`). */
export interface PermissionModuleRecord {
  id: string
  code: string
  name: string
  description: string
}
