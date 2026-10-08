import { PermissionCodes } from "@/lib/auth/permission-codes"

/** Any single template capability opens the Workflow Templates screens - same rule as the sidebar item and the backend's list/get endpoints. */
export const WORKFLOW_TEMPLATE_ANY_PERMISSION = [
  PermissionCodes.WA_TEMPLATES_CREATE,
  PermissionCodes.WA_TEMPLATES_UPDATE,
  PermissionCodes.WA_TEMPLATES_DELETE,
  PermissionCodes.WA_TEMPLATES_TOGGLE_ACTIVE,
]

/** Either decision on a task also allows claiming/releasing it (the backend's ACT rule). */
export const WORKFLOW_TASK_ACT_PERMISSION = [PermissionCodes.WA_TASK_APPROVE, PermissionCodes.WA_TASK_REJECT]

/** Any single department capability opens the Departments admin screens. */
export const DEPARTMENT_ANY_PERMISSION = [
  PermissionCodes.OTUA_DEPARTMENTS_VIEW,
  PermissionCodes.OTUA_DEPARTMENTS_CREATE,
  PermissionCodes.OTUA_DEPARTMENTS_UPDATE,
  PermissionCodes.OTUA_DEPARTMENTS_DELETE,
]

/** Who may read the department list - the backend also lets template designers read it, to route a stage to one. */
export const DEPARTMENT_READ_PERMISSION = [
  PermissionCodes.OTUA_DEPARTMENTS_VIEW,
  PermissionCodes.OTUA_DEPARTMENTS_UPDATE,
  PermissionCodes.WA_TEMPLATES_CREATE,
  PermissionCodes.WA_TEMPLATES_UPDATE,
]
