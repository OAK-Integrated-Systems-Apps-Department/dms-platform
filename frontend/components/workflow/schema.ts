import { z } from "zod"

/** Matches the backend's CreateWorkflowTemplateRequest code pattern - stored upper-case, never changes. */
const TEMPLATE_CODE_PATTERN = /^[A-Za-z0-9_-]{2,50}$/

const templateDetailsShape = {
  name: z.string().trim().min(1, "Name is required").max(150, "Name must be under 150 characters"),
  description: z.string().trim().max(1000, "Keep the description under 1000 characters"),
  documentTypeId: z.string().min(1, "Pick a document type"),
  onDocumentChange: z.enum(["flag_only", "restart"]),
}

/** Create Workflow Template form - `code` is only accepted here. */
export const createTemplateSchema = z.object({
  ...templateDetailsShape,
  code: z
    .string()
    .trim()
    .regex(TEMPLATE_CODE_PATTERN, "2-50 letters, digits, '_' or '-'"),
})
export type CreateTemplateFormValues = z.infer<typeof createTemplateSchema>

/** Edit details of a draft - code stays fixed. */
export const updateTemplateSchema = z.object(templateDetailsShape)
export type UpdateTemplateFormValues = z.infer<typeof updateTemplateSchema>

/**
 * One stage, as edited on its card. Matches UpsertWorkflowStageRequest's
 * limits, including the Phase 2 rules: a reminder needs an SLA and comes
 * before it; a named-user escalation needs the person.
 */
export const stageSchema = z
  .object({
  name: z.string().trim().min(1, "Stage name is required").max(150, "Name must be under 150 characters"),
  completionRule: z.enum(["all", "any", "majority"]),
  rejectionRule: z.enum(["halt_immediately", "wait_for_all"]),
  slaHours: z
    .number()
    .int("Whole hours only")
    .min(1, "At least 1 hour")
    .max(8760, "At most 8760 hours (one year)")
    .nullable(),
  reminderBeforeHours: z.number().int("Whole hours only").min(1, "At least 1 hour").nullable(),
  escalationTarget: z.enum(["none", "department_head", "named_user"]),
  escalationUserId: z.string().nullable(),
  allowInfoRequests: z.boolean(),
  participants: z
    .array(
      z.object({
        assigneeType: z.enum(["user", "role", "department"]),
        assigneeId: z.string(),
        displayName: z.string().nullable(),
      })
    )
    .min(1, "Add at least one participant")
    .max(50, "At most 50 participants per stage"),
  })
  .superRefine((stage, ctx) => {
    if (stage.reminderBeforeHours != null) {
      if (stage.slaHours == null) {
        ctx.addIssue({ code: "custom", path: ["reminderBeforeHours"], message: "Set SLA hours to send a reminder" })
      } else if (stage.reminderBeforeHours >= stage.slaHours) {
        ctx.addIssue({ code: "custom", path: ["reminderBeforeHours"], message: "Must be less than the SLA hours" })
      }
    }
    if (stage.escalationTarget !== "none" && stage.slaHours == null) {
      ctx.addIssue({ code: "custom", path: ["escalationTarget"], message: "Escalation happens at the due time - set SLA hours" })
    }
    if (stage.escalationTarget === "named_user" && !stage.escalationUserId) {
      ctx.addIssue({ code: "custom", path: ["escalationUserId"], message: "Choose who to escalate to" })
    }
  })
export type StageFormValues = z.infer<typeof stageSchema>

/** Rejecting needs a real reason (WA-FR-003); approving takes an optional comment. Both max 2000 like the backend. */
export const rejectSchema = z.object({
  text: z.string().trim().min(1, "A reason is required to reject").max(2000, "Keep it under 2000 characters"),
})
export const approveSchema = z.object({
  text: z.string().trim().max(2000, "Keep it under 2000 characters"),
})
export type TaskDecisionFormValues = z.infer<typeof rejectSchema>
