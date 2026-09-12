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
      { label: "Users", icon: Users, href: "/admin/users", permission: [PermissionCodes.OTUA_USERS_VIEW, PermissionCodes.OTUA_USERS_UPDATE] },
      { label: "Roles", icon: Shield, href: "/admin/roles", permission: [PermissionCodes.ASM_ROLES_VIEW_ROLE, PermissionCodes.ASM_ROLES_UPDATE_ROLE] },
      // Permissions catalog is served by PermissionController, which was NOT
      // part of the granular sweep (see UserController/UserRoleController/
      // RoleController/RolePermissionController) - it still checks the
      // legacy codes, so this nav item must too.
      { label: "Permissions", icon: KeyRound, href: "/admin/permissions", permission: [PermissionCodes.LEGACY_ROLE_VIEW, PermissionCodes.LEGACY_ROLE_MANAGE] },
      { label: "Audit Log", icon: ScrollText },
    ],
  },
]