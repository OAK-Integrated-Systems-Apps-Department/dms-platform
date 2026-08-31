import type { PermissionGroup, RoleRecord } from "./types"

/**
 * Permissions grouped by DMS functional area, matching the grouping used
 * on the Role Permission Configuration wireframe screen. Swap for a
 * fetch/query hook later — see `components/dashboard/data.ts` for the
 * same pattern.
 */
export const permissionGroups: PermissionGroup[] = [
  {
    area: "Document Management",
    permissions: [
      { id: "doc.upload", label: "Upload documents" },
      { id: "doc.delete", label: "Delete documents" },
      { id: "doc.download", label: "Download documents" },
    ],
  },
  {
    area: "User & Role Administration",
    permissions: [
      { id: "admin.manage-users", label: "Manage users" },
      { id: "admin.manage-roles", label: "Manage roles" },
      { id: "admin.view-audit", label: "View audit history" },
    ],
  },
  {
    area: "Search & Retrieval",
    permissions: [
      { id: "search.basic", label: "Search documents" },
      { id: "search.advanced", label: "Advanced search & filters" },
      { id: "search.export", label: "Export search results" },
    ],
  },
  {
    area: "Workflow / Approvals",
    permissions: [
      { id: "workflow.approve", label: "Approve workflow step" },
      { id: "workflow.reject", label: "Reject workflow step" },
      { id: "workflow.reassign", label: "Reassign workflow task" },
    ],
  },
]

const allPermissionIds = permissionGroups.flatMap((group) =>
  group.permissions.map((permission) => permission.id)
)

export const initialRoles: RoleRecord[] = [
  {
    id: "role-admin",
    name: "Admin",
    description:
      "Full administrative access, including user, role, and system configuration.",
    status: "Active",
    assignedUsers: 2,
    permissions: allPermissionIds,
    system: true,
  },
  {
    id: "role-compliance-officer",
    name: "Compliance Officer",
    description:
      "Reviews audit history and manages retention and compliance-related settings.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "doc.download",
      "admin.view-audit",
      "search.basic",
      "search.advanced",
      "search.export",
    ],
  },
  {
    id: "role-approver",
    name: "Approver",
    description:
      "Reviews and approves or rejects documents routed through a workflow.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "doc.download",
      "search.basic",
      "workflow.approve",
      "workflow.reject",
      "workflow.reassign",
    ],
  },
  {
    id: "role-uploader",
    name: "Uploader",
    description:
      "Uploads new documents and submits them into workflows for approval.",
    status: "Active",
    assignedUsers: 3,
    permissions: ["doc.upload", "doc.download", "search.basic"],
  },
  {
    id: "role-viewer",
    name: "Viewer",
    description:
      "Read-only access to search and view documents shared with them.",
    status: "Inactive",
    assignedUsers: 3,
    permissions: ["doc.download", "search.basic"],
  },
]
