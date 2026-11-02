import { z } from "zod"

/** Matches the backend's CreateWorkflowTemplateRequest code pattern - stored upper-case, never changes. */
const TEMPLATE_CODE_PATTERN = /^[A-Za-z0-9_-]{2,50}$/

/** Whole hours, 1 to a year - the backend's @Min(1) @Max(8760); null = not set. */
function hoursField(max = 8760) {
  return z
    .number()
    .int("Whole hours only")
    .min(1, "At least 1 hour")
    .max(max, `At most ${max} hours`)
    .nullable()
}

const templateDetailsShape = {
  name: z.string().trim().min(1, "Name is required").max(150, "Name must be under 150 characters"),
  description: z.string().trim().max(1000, "Keep the description under 1000 characters"),
  documentTypeId: z.string().min(1, "Pick a document type"),
  onDocumentChange: z.enum(["flag_only", "restart"]),
  // Inactivity defaults for every stage (N-FR-006); a stage's own value overrides them.
  defaultInactivityReminderHours: hoursField(),
  defaultEscalateAfterReminderHours: hoursField(),
}

interface TemplateDefaultsValues {
  defaultInactivityReminderHours: number | null
  defaultEscalateAfterReminderHours: number | null
}

/** The same rule the backend enforces: escalation follows the inactivity reminder. */
function templateDefaultsRules(values: TemplateDefaultsValues, ctx: z.RefinementCtx) {
  if (values.defaultEscalateAfterReminderHours != null && values.defaultInactivityReminderHours == null) {
    ctx.addIssue({
      code: "custom",
      path: ["defaultEscalateAfterReminderHours"],
      message: "Set an inactivity reminder first - escalation follows it",
    })
  }
}

/** Create Workflow Template form - `code` is only accepted here. */
export const createTemplateSchema = z
  .object({
    ...templateDetailsShape,
    code: z
      .string()
      .trim()
      .regex(TEMPLATE_CODE_PATTERN, "2-50 letters, digits, '_' or '-'"),
  })
  .superRefine(templateDefaultsRules)
export type CreateTemplateFormValues = z.infer<typeof createTemplateSchema>

/** Edit details of a draft - code stays fixed. */
export const updateTemplateSchema = z.object(templateDetailsShape).superRefine(templateDefaultsRules)
export type UpdateTemplateFormValues = z.infer<typeof updateTemplateSchema>

/**
 * What a stage inherits when it leaves an inactivity field empty - the template's defaults. Passed to the stage
 * card so it can show the inherited value and check a stage against what it will actually inherit.
 */
export interface TemplateReminderDefaults {
  inactivityReminderHours: number | null
  escalateAfterReminderHours: number | null
}

/**
 * One stage, as edited on its card. Matches UpsertWorkflowStageRequest's
 * limits, including the Phase 2 rules: a reminder needs an SLA and comes
 * before it; a named-user escalation needs the person. The inactivity rules depend on what the stage inherits
 * from its template, so the schema is built for those defaults: escalate-after needs an inactivity reminder (the
 * stage's own or the template's) and an escalation target on the stage, and a stage that escalates needs
 * something that triggers it - an SLA (the due time) or an inactivity reminder with an escalate-after time.
 */
export function buildStageSchema(defaults?: TemplateReminderDefaults | null) {
  return z
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
      /** null = inherit the template default */
      inactivityReminderHours: hoursField(),
      escalateAfterReminderHours: hoursField(),
      escalationTarget: z.enum(["none", "department_head", "named_user"]),
      escalationUserId: z.string().nullable(),
      allowInfoRequests: z.boolean(),
      /** Always true (V117) - not actually sent to the backend, which ignores the field now. Kept so the form has a value to display. */
      requiresSignature: z.boolean(),
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
      const inactivity = stage.inactivityReminderHours ?? defaults?.inactivityReminderHours ?? null
      const escalateAfter = stage.escalateAfterReminderHours ?? defaults?.escalateAfterReminderHours ?? null
      if (stage.escalateAfterReminderHours != null && inactivity == null) {
        ctx.addIssue({
          code: "custom",
          path: ["escalateAfterReminderHours"],
          message: "Needs an inactivity reminder - set one for this stage, or a default on the template",
        })
      }
      if (stage.escalateAfterReminderHours != null && stage.escalationTarget === "none") {
        ctx.addIssue({
          code: "custom",
          path: ["escalationTarget"],
          message: "Choose who to escalate to - escalate-after needs an escalation target",
        })
      } else if (stage.escalationTarget !== "none" && stage.slaHours == null && !(inactivity != null && escalateAfter != null)) {
        ctx.addIssue({
          code: "custom",
          path: ["escalationTarget"],
          message: "Nothing would trigger the escalation - set SLA hours (the due time), or an inactivity reminder with an escalate-after time",
        })
      }
      if (stage.escalationTarget === "named_user" && !stage.escalationUserId) {
        ctx.addIssue({ code: "custom", path: ["escalationUserId"], message: "Choose who to escalate to" })
      }
    })
}

export const stageSchema = buildStageSchema()
export type StageFormValues = z.infer<typeof stageSchema>

/** Rejecting needs a real reason (WA-FR-003); approving takes an optional comment. Both max 2000 like the backend. */
export const rejectSchema = z.object({
  text: z.string().trim().min(1, "A reason is required to reject").max(2000, "Keep it under 2000 characters"),
})
export const approveSchema = z.object({
  text: z.string().trim().max(2000, "Keep it under 2000 characters"),
})
export type TaskDecisionFormValues = z.infer<typeof rejectSchema>
