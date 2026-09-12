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
      { label: "Users", icon: Users, href: "/admin/users", permission: ["otua.manage-users.view", "otua.manage-users.update"] },
      { label: "Roles", icon: Shield, href: "/admin/roles", permission: ["asm.manage-roles-permissions.view-role", "asm.manage-roles-permissions.update-role"] },
      // Permissions catalog is served by PermissionController, which was NOT
      // part of the granular sweep (see UserController/UserRoleController/
      // RoleController/RolePermissionController) - it still checks the
      // legacy role.view/role.manage codes, so this nav item must too.
      { label: "Permissions", icon: KeyRound, href: "/admin/permissions", permission: ["role.view", "role.manage"] },
      { label: "Audit Log", icon: ScrollText },
    ],
  },
]