import {
  AtSign,
  BarChart3,
  Building2,
  FileStack,
  FileText,
  Grid3x3,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  LockKeyhole,
  Pin,
  ScrollText,
  Search,
  Shield,
  ShieldAlert,
  Trash2,
  Users,
  Workflow,
  Waypoints
} from "lucide-react"

import { PermissionCodes } from "@/lib/auth/permission-codes"

import type { NavSection } from "./types"

/**
 * Sidebar navigation. Add, remove or reorder items/sections here — the
 * sidebar component just renders whatever this array contains.
 */
export const navSections: NavSection[] = [
  {
    items: [
      { label: "Dashboard", icon: LayoutDashboard, href: "/" },
      {
        label: "Shared With Me",
        icon: Users,
        href: "/shared",
        permission: PermissionCodes.DOCM_DOCUMENTS_VIEW,
      },
      {
        label: "Recycle Bin",
        icon: Trash2,
        href: "/recycle-bin",
        permission: [
          PermissionCodes.RBR_RESTORE,
          PermissionCodes.RBR_PURGE,
          PermissionCodes.RBR_RETENTION,
        ],
      },
      { label: "Recent Pins", icon: Pin },
      // The caller's workflow approval queue - visible to everyone (anyone
      // can be named as an approver); the badge is their live open-task count.
      { label: "My Tasks", icon: Workflow, href: "/tasks", liveBadge: "myOpenTasks" },
      // Comments that mention me - readable even without document access (Phase 2).
      { label: "Mentions", icon: AtSign, href: "/mentions" },
      { label: "Search", icon: Search },
      { label: "Reports", icon: BarChart3 },
    ],
  },
  {
    label: "Documents",
    items: [
      // Bulk Import (with Import Templates under it) is a tab inside My
      // Documents (DocumentsHub), on its own /documents/... path.
      {
        label: "My Documents",
        icon: FileText,
        href: "/documents",
        permission: PermissionCodes.DOCM_DOCUMENTS_VIEW,
      },
    ],
  },
  {
    label: "Governance",
    items: [
      // Any single otua.manage-users.* capability gets you into the Users
      // section — not just view/update. Someone who can only create users,
      // or only reset passwords, or only activate/deactivate, still needs
      // a way in; the User List page itself (app/admin/users/page.tsx)
      // decides what to actually show once they're there (the full table
      // needs view or update — see that page's own canBrowseList).
      {
        label: "Users",
        icon: Users,
        href: "/admin/users",
        permission: [
          PermissionCodes.OTUA_USERS_VIEW,
          PermissionCodes.OTUA_USERS_UPDATE,
          PermissionCodes.OTUA_USERS_CREATE,
          PermissionCodes.OTUA_USERS_RESET_PASSWORD,
          PermissionCodes.OTUA_USERS_ACTIVATE,
          PermissionCodes.OTUA_USERS_DEACTIVATE,
          PermissionCodes.OTUA_USERS_DELETE,
          PermissionCodes.OTUA_USERS_ASSIGN_ROLE,
        ],
      },
      {
        label: "Roles",
        icon: Shield,
        href: "/admin/roles",
        permission: [
          PermissionCodes.ASM_ROLES_VIEW_ROLE,
          PermissionCodes.ASM_ROLES_UPDATE_ROLE,
        ],
      },
      // Permissions catalog is served by PermissionController, which was NOT
      // part of the granular sweep (see UserController/UserRoleController/
      // RoleController/RolePermissionController) - it still checks the
      // legacy codes, so this nav item must too.
      {
        label: "Permissions",
        icon: KeyRound,
        href: "/admin/permissions",
        permission: [
          PermissionCodes.LEGACY_ROLE_VIEW,
          PermissionCodes.LEGACY_ROLE_MANAGE,
        ],
      },
      {
        label: "Audit Log",
        icon: ScrollText,
        href: "/admin/audit-log",
        permission: PermissionCodes.ABDR_VIEW_AUDIT_HISTORY,
      },
      // Departments double as workflow approver pools.
      {
        label: "Departments",
        icon: Building2,
        href: "/admin/departments",
        permission: [
          PermissionCodes.OTUA_DEPARTMENTS_VIEW,
          PermissionCodes.OTUA_DEPARTMENTS_CREATE,
          PermissionCodes.OTUA_DEPARTMENTS_UPDATE,
          PermissionCodes.OTUA_DEPARTMENTS_DELETE,
        ],
      },
      // Approval chain design (WA). Any single template capability gets you
      // in - the template page shows only the actions you hold.
      {
        label: "Workflow Templates",
        icon: Waypoints,
        href: "/admin/workflows",
        permission: [
          PermissionCodes.WA_TEMPLATES_CREATE,
          PermissionCodes.WA_TEMPLATES_UPDATE,
          PermissionCodes.WA_TEMPLATES_DELETE,
          PermissionCodes.WA_TEMPLATES_TOGGLE_ACTIVE,
        ],
      },
      // Malware-scan quarantine review (VMSBDI FR-005). Lives with the other
      // security-control screens in Governance rather than a group of its
      // own. Any single quarantine capability gets you in - the detail page
      // shows only the actions you hold.
      {
        label: "Quarantine",
        icon: ShieldAlert,
        href: "/admin/quarantine",
        permission: [
          PermissionCodes.DOCM_QUARANTINE_VIEW,
          PermissionCodes.DOCM_QUARANTINE_RELEASE,
          PermissionCodes.DOCM_QUARANTINE_DELETE,
          PermissionCodes.DOCM_QUARANTINE_OVERRIDE,
        ],
      },
      // Encryption at rest (Module DE): the master key, rotation, re-wrapping.
      {
        label: "Encryption Keys",
        icon: LockKeyhole,
        href: "/admin/encryption",
        permission: [
          PermissionCodes.DE_KEYS_VIEW,
          PermissionCodes.DE_KEYS_ROTATE,
          PermissionCodes.DE_KEYS_GENERATE,
        ],
      },
      // Every organisation on the platform - suspend, deactivate, destroy keys.
      {
        label: "Organisations",
        icon: Building2,
        href: "/admin/organisations",
        platformAdminOnly: true,
      },
    ],
  },
  {
    label: "Metadata",
    items: [
      {
        label: "Metadata Fields",
        icon: ListChecks,
        href: "/admin/metadata/fields",
        permission: [
          PermissionCodes.MDTM_METADATA_SCHEMA_VIEW_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_CREATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_DELETE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_DEPRECATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES,
        ],
      },
      {
        label: "Document Types",
        icon: FileStack,
        href: "/admin/metadata/document-types",
        permission: [
          PermissionCodes.MDTM_CONTENT_TYPES_VIEW,
          PermissionCodes.MDTM_CONTENT_TYPES_CREATE,
          PermissionCodes.MDTM_CONTENT_TYPES_UPDATE,
          PermissionCodes.MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE,
          // Template managers reach a type's Templates tab from here.
          PermissionCodes.MDTM_DOCUMENT_TEMPLATES_CREATE,
          PermissionCodes.MDTM_DOCUMENT_TEMPLATES_UPDATE,
          PermissionCodes.MDTM_DOCUMENT_TEMPLATES_DELETE,
        ],
      },
      {
        label: "Type Field Mapping",
        icon: Grid3x3,
        href: "/admin/metadata/mapping",
        permission: [
          PermissionCodes.MDTM_TYPE_FIELD_MAPPING_VIEW,
          PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE,
        ],
      },
    ],
  },
]