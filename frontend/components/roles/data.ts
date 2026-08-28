import type { PermissionGroup, RoleAssignedUser, RoleRecord } from "./types"

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

/**
 * Assigned Users table on the Role Details screen (R4), keyed by role id.
 * Row counts match each role's `assignedUsers` above. Swap for a
 * `GET /admin/realms/{realm}/roles/{role-name}/users` fetch/query hook —
 * see the caveat on the R4 wireframe screen.
 */
export const assignedUsersByRole: Record<string, RoleAssignedUser[]> = {
  "role-admin": [
    { id: "u-1", name: "Priya Raman", username: "praman", status: "Active" },
    {
      id: "u-2",
      name: "Owen Castillo",
      username: "ocastillo",
      status: "Active",
    },
  ],
  "role-compliance-officer": [
    {
      id: "u-3",
      name: "Marta Lindqvist",
      username: "mlindqvist",
      status: "Active",
    },
    { id: "u-4", name: "Daniel Osei", username: "dosei", status: "Active" },
  ],
  "role-approver": [
    { id: "u-5", name: "Renata Alves", username: "ralves", status: "Active" },
    {
      id: "u-6",
      name: "Hassan Farouk",
      username: "hfarouk",
      status: "Inactive",
    },
  ],
  "role-uploader": [
    { id: "u-7", name: "Ines Dubois", username: "idubois", status: "Active" },
    {
      id: "u-8",
      name: "Kenji Watanabe",
      username: "kwatanabe",
      status: "Active",
    },
    {
      id: "u-9",
      name: "Sofia Marchetti",
      username: "smarchetti",
      status: "Active",
    },
  ],
  "role-viewer": [
    { id: "u-10", name: "Tomas Nowak", username: "tnowak", status: "Inactive" },
    {
      id: "u-11",
      name: "Grace Odhiambo",
      username: "godhiambo",
      status: "Active",
    },
    {
      id: "u-12",
      name: "Liam Fitzgerald",
      username: "lfitzgerald",
      status: "Active",
    },
  ],
}
