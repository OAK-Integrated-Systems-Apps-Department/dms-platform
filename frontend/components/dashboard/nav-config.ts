import {
  BarChart3,
  FileText,
  KeyRound,
  LayoutDashboard,
  Pin,
  ScrollText,
  Search,
  Shield,
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
      { label: "Shared With Me", icon: Users },
      { label: "Recent Pins", icon: Pin },
      { label: "Workflows", icon: Workflow, badge: 3 },
      { label: "Search", icon: Search },
      { label: "Reports", icon: BarChart3 },
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
    ],
  },
]