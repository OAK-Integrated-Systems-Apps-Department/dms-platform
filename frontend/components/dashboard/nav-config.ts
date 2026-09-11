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
      { label: "Users", icon: Users, href: "/admin/users", permission: ["user.view", "user.manage"] },
      { label: "Roles", icon: Shield, href: "/admin/roles", permission: ["role.view", "role.manage"] },
      { label: "Permissions", icon: KeyRound, href: "/admin/permissions", permission: ["role.view", "role.manage"] },
      { label: "Audit Log", icon: ScrollText },
    ],
  },
]