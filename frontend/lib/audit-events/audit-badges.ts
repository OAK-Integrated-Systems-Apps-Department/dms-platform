import type { AuditEventResponseDto } from "./api"

type Outcome = AuditEventResponseDto["outcome"]
type Severity = NonNullable<AuditEventResponseDto["severity"]>

/** Human-readable label per outcome/severity value - shared by the Filters panel's dropdowns and anywhere else a value needs display text rather than the raw enum string. */
export const OUTCOME_LABELS: Record<Outcome, string> = { SUCCESS: "Success", FAILURE: "Failure", DENIED: "Denied" }
export const SEVERITY_LABELS: Record<Severity, string> = { LOW: "Low", MEDIUM: "Medium", HIGH: "High" }

/** Badge color variant per outcome/severity value - shared by the list table and the detail page so a future style change (e.g. giving DENIED its own color instead of sharing FAILURE's) only needs to happen in one place. */
export const OUTCOME_BADGE_VARIANT: Record<Outcome, "default" | "rust"> = {
  SUCCESS: "default",
  FAILURE: "rust",
  DENIED: "rust",
}
export const SEVERITY_BADGE_VARIANT: Record<Severity, "muted" | "gold" | "rust"> = {
  LOW: "muted",
  MEDIUM: "gold",
  HIGH: "rust",
}
