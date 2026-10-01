import {
  BarChart3,
  Download,
  FileDown,
  FileSpreadsheet,
  FileStack,
  FileText,
  FileUp,
  Grid3x3,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  Pin,
  ScrollText,
  Search,
  Shield,
  ShieldAlert,
  Users,
  Workflow,
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
      { label: "My Documents", icon: FileText },
      { label: "My Exports", icon: Download, href: "/exports" },
      { label: "Shared With Me", icon: Users },
      { label: "Recent Pins", icon: Pin },
      { label: "Workflows", icon: Workflow, badge: 3 },
      { label: "Search", icon: Search },
      { label: "Reports", icon: BarChart3 },
    ],
  },
  {
    label: "Documents",
    items: [
      { label: "Bulk Import", icon: FileUp, href: "/documents/bulk-import", permission: PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE },
      { label: "Bulk Export", icon: FileDown, href: "/documents/bulk-export", permission: PermissionCodes.FIE_BULK_EXPORT_EXECUTE },
      {
        label: "Import Templates", icon: FileSpreadsheet, href: "/documents/import-templates", permission: [
          PermissionCodes.FIE_IMPORT_TEMPLATES_CREATE,
          PermissionCodes.FIE_IMPORT_TEMPLATES_UPDATE,
          PermissionCodes.FIE_IMPORT_TEMPLATES_DELETE,
        ]
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
        label: "Users", icon: Users, href: "/admin/users", permission: [
          PermissionCodes.OTUA_USERS_VIEW,
          PermissionCodes.OTUA_USERS_UPDATE,
          PermissionCodes.OTUA_USERS_CREATE,
          PermissionCodes.OTUA_USERS_RESET_PASSWORD,
          PermissionCodes.OTUA_USERS_ACTIVATE,
          PermissionCodes.OTUA_USERS_DEACTIVATE,
          PermissionCodes.OTUA_USERS_DELETE,
          PermissionCodes.OTUA_USERS_ASSIGN_ROLE,
        ]
      },
      { label: "Roles", icon: Shield, href: "/admin/roles", permission: [PermissionCodes.ASM_ROLES_VIEW_ROLE, PermissionCodes.ASM_ROLES_UPDATE_ROLE] },
      // Permissions catalog is served by PermissionController, which was NOT
      // part of the granular sweep (see UserController/UserRoleController/
      // RoleController/RolePermissionController) - it still checks the
      // legacy codes, so this nav item must too.
      { label: "Permissions", icon: KeyRound, href: "/admin/permissions", permission: [PermissionCodes.LEGACY_ROLE_VIEW, PermissionCodes.LEGACY_ROLE_MANAGE] },
      { label: "Audit Log", icon: ScrollText, href: "/admin/audit-log", permission: PermissionCodes.ABDR_VIEW_AUDIT_HISTORY },
      // Malware-scan quarantine review (VMSBDI FR-005). Lives with the other
      // security-control screens in Governance rather than a group of its
      // own. Any single quarantine capability gets you in - the detail page
      // shows only the actions you hold.
      {
        label: "Quarantine", icon: ShieldAlert, href: "/admin/quarantine", permission: [
          PermissionCodes.DOCM_QUARANTINE_VIEW,
          PermissionCodes.DOCM_QUARANTINE_RELEASE,
          PermissionCodes.DOCM_QUARANTINE_DELETE,
          PermissionCodes.DOCM_QUARANTINE_OVERRIDE,
        ]
      },
    ],
  },
  {
    label: "Metadata",
    items: [
      {
        label: "Metadata Fields", icon: ListChecks, href: "/admin/metadata/fields", permission: [
          PermissionCodes.MDTM_METADATA_SCHEMA_VIEW_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_CREATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_UPDATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_DELETE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_DEPRECATE_FIELD,
          PermissionCodes.MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES,
        ]
      },
      {
        label: "Document Types", icon: FileStack, href: "/admin/metadata/document-types", permission: [
          PermissionCodes.MDTM_CONTENT_TYPES_VIEW,
          PermissionCodes.MDTM_CONTENT_TYPES_CREATE,
          PermissionCodes.MDTM_CONTENT_TYPES_UPDATE,
          PermissionCodes.MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE,
        ]
      },
      {
        label: "Type Field Mapping", icon: Grid3x3, href: "/admin/metadata/mapping", permission: [
          PermissionCodes.MDTM_TYPE_FIELD_MAPPING_VIEW,
          PermissionCodes.MDTM_TYPE_FIELD_MAPPING_UPDATE,
        ]
      },
    ],
  },
]