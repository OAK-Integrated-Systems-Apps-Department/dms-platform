/**
 * A single grantable permission in the platform-wide catalog (mirrors
 * `PermissionResponse.java`). Not tenant-scoped — every tenant's roles
 * draw from this same catalog via Role Permission Configuration; only the
 * *grant* of a permission to a role is tenant-scoped, not the permission
 * itself.
 */
export interface PermissionRecord {
  id: string
  /** Stable machine key checked by `@authz.hasPermission(...)` throughout the backend, e.g. "document.approve". Immutable after creation. */
  code: string
  name: string
  description: string
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
