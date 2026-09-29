import { CheckCircle2, Folder, Upload, Workflow, XCircle } from "lucide-react"

import type { ActivityEntry, AppUser, ApprovalTask, RecentDocument } from "./types"

/**
 * Mock data for the Home dashboard. Swap the contents of these values (or
 * replace this file with a fetch/query hook) to wire up real data — the
 * components that consume them don't need to change.
 */

export const currentUser: AppUser = {
  name: "Priyanka Fernando",
  role: "Compliance Officer",
  initials: "PF",
}

export const recentDocuments: RecentDocument[] = [
  {
    id: "doc-1",
    name: "Vendor MSA — Acme Textiles.pdf",
    type: "pdf",
    owner: "You",
    modified: "12 min ago",
    size: "1.2 MB",
    status: "Approved",
  },
  {
    id: "doc-2",
    name: "Q3 Financial Statement.xlsx",
    type: "xlsx",
    owner: "Nadia R.",
    modified: "48 min ago",
    size: "884 KB",
    status: "Pending",
  },
  {
    id: "doc-3",
    name: "Board Resolution — Aug 2026.docx",
    type: "docx",
    owner: "Kasun P.",
    modified: "2 hr ago",
    size: "312 KB",
    status: "Draft",
  },
  {
    id: "doc-4",
    name: "NDA — Horizon Logistics.pdf",
    type: "pdf",
    owner: "You",
    modified: "Yesterday",
    size: "540 KB",
    status: "Approved",
  },
  {
    id: "doc-5",
    name: "Audit Checklist — FY26.xlsx",
    type: "xlsx",
    owner: "Malik S.",
    modified: "Yesterday",
    size: "1.8 MB",
    status: "Rejected",
  },
]

export const pendingApprovals: ApprovalTask[] = [
  {
    id: "approval-1",
    name: "Lease Renewal — Colombo Office.pdf",
    requester: "Nadia R.",
    due: "Due today",
    size: "2.1 MB",
  },
  {
    id: "approval-2",
    name: "Employee Handbook v4.docx",
    requester: "Kasun P.",
    due: "Due tomorrow",
    size: "760 KB",
  },
  {
    id: "approval-3",
    name: "Supplier Onboarding — Ceylon Foods.pdf",
    requester: "Malik S.",
    due: "Due in 2 days",
    size: "1.4 MB",
  },
]

export const activityFeed: ActivityEntry[] = [
  {
    id: "activity-1",
    who: "Nadia R.",
    action: "uploaded",
    target: "Q3 Financial Statement.xlsx",
    time: "48 min ago",
    icon: Upload,
  },
  {
    id: "activity-2",
    who: "You",
    action: "approved",
    target: "Vendor MSA — Acme Textiles.pdf",
    time: "1 hr ago",
    icon: CheckCircle2,
  },
  {
    id: "activity-3",
    who: "Malik S.",
    action: "rejected",
    target: "Audit Checklist — FY26.xlsx",
    time: "3 hr ago",
    icon: XCircle,
  },
  {
    id: "activity-4",
    who: "Kasun P.",
    action: "created folder",
    target: "FY26 Board Resolutions",
    time: "Yesterday",
    icon: Folder,
  },
  {
    id: "activity-5",
    who: "System",
    action: "ran workflow",
    target: "Monthly Compliance Review",
    time: "Yesterday",
    icon: Workflow,
  },
]
