import {
  BarChart3,
  FileText,
  LayoutDashboard,
  Pin,
  ScrollText,
  Search,
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
      { label: "Users & Roles", icon: Users, href: "/admin/roles" },
      { label: "Audit Log", icon: ScrollText },
    ],
  },
]
