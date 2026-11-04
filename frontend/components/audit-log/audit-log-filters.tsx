"use client"

import { useEffect, useRef, useState } from "react"
import { ChevronDown, Download, SlidersHorizontal, X } from "lucide-react"

import { DateTimePicker } from "@/components/ui/datetime-picker"
import { Label } from "@/components/ui/label"
import { MultiSelect } from "@/components/ui/multi-select"
import { SearchableSelect } from "@/components/ui/searchable-select"
import {
  AUDIT_ACTION_CATALOG,
  AUDIT_MODULE_LABELS,
  auditActionOptionsForModules,
  auditModuleOptions,
} from "@/lib/audit-events/audit-action-catalog"
import {
  OUTCOME_LABELS,
  SEVERITY_LABELS,
} from "@/lib/audit-events/audit-badges"
import type { AuditEventResponseDto } from "@/lib/audit-events/api"
import { cn, formatDateTime } from "@/lib/utils"

import { ActorFilterSelect, type ActorOption } from "./actor-filter-select"

export interface AuditLogFilterState {
  modules: string[]
  actions: string[]
  actorIds: string[]
  objectTypes: string[]
  objectId: string
  outcome: AuditEventResponseDto["outcome"] | ""
  severity: NonNullable<AuditEventResponseDto["severity"]> | ""
  /** Raw `<input type="datetime-local">` value ("YYYY-MM-DDTHH:mm", no seconds/offset) - converted to a full ISO date-time bound by the page before it reaches the API. */
  occurredFrom: string
  occurredTo: string
  /**
   * Not manually enterable through this panel - set only by the Audit
   * Event detail page's "Part of operation" link (`?correlationId=...`),
   * which `AuditLogPage` reads on mount/navigation. Still shown as a
   * removable chip and included in "Clear all" like every other filter.
   */
  correlationId: string
}

export const EMPTY_AUDIT_LOG_FILTERS: AuditLogFilterState = {
  modules: [],
  actions: [],
  actorIds: [],
  objectTypes: [],
  objectId: "",
  outcome: "",
  severity: "",
  occurredFrom: "",
  occurredTo: "",
  correlationId: "",
}

/** Mirrors backend/dms-core's AuditObjectTypes.java - every objectType() call across RoleServiceImpl/RolePermissionServiceImpl/PermissionServiceImpl/PermissionActionServiceImpl/PermissionModuleServiceImpl/UserServiceImpl/AuthService/PasswordResetService/UserRoleServiceImpl/AuditEventServiceImpl/OutboxRelay/TenantServiceImpl/TenantProvisioningService goes through that one shared constants class, so this list only needs to be kept in sync with it, not with every individual call site. */
const OBJECT_TYPE_OPTIONS = [
  "ROLE",
  "ROLE_PERMISSION",
  "PERMISSION",
  "PERMISSION_ACTION",
  "PERMISSION_MODULE",
  "USER",
  "AUDIT_EVENT",
  "OUTBOX_EVENT",
  "TENANT",
  "ENDPOINT",
  "DOCUMENT_TYPE",
  "DOCUMENT_TYPE_SCHEMA",
  "METADATA_FIELD",
  "METADATA_FIELD_OPTION",
  "METADATA_FIELD_VALIDATION",
  "DOCUMENT_RELATIONSHIP",
  "RELATIONSHIP_TYPE",
  "OWNERSHIP_BATCH",
]

/** Derived from the shared audit-badges.ts label maps, not re-typed here, so the Outcome/Severity dropdown options can't drift from the chip labels built from those same maps below. */
const OUTCOME_OPTIONS = Object.entries(OUTCOME_LABELS).map(
  ([value, label]) => ({ value, label })
)
const SEVERITY_OPTIONS = Object.entries(SEVERITY_LABELS).map(
  ([value, label]) => ({ value, label })
)

/** Drops any selected actions that no longer belong to ANY of `modules` - a module deselected (via the panel or a chip) can't leave a now out-of-scope action behind. */
function validActionsForModules(
  modules: string[],
  actions: string[]
): string[] {
  const stillValid = new Set(
    auditActionOptionsForModules(modules).map((entry) => entry.action)
  )
  return actions.filter((action) => stillValid.has(action))
}

function countActive(filters: AuditLogFilterState): number {
  return (
    (filters.modules.length > 0 ? 1 : 0) +
    (filters.actions.length > 0 ? 1 : 0) +
    (filters.actorIds.length > 0 ? 1 : 0) +
    (filters.objectTypes.length > 0 ? 1 : 0) +
    (filters.objectId.trim() ? 1 : 0) +
    (filters.outcome ? 1 : 0) +
    (filters.severity ? 1 : 0) +
    (filters.occurredFrom ? 1 : 0) +
    (filters.occurredTo ? 1 : 0) +
    (filters.correlationId ? 1 : 0)
  )
}

interface Chip {
  key: string
  label: string
  onRemove: () => void
}

/** One removable chip per active value (not per field) - a multi-select field with 3 modules picked shows 3 chips, each independently removable. */
function buildChips(
  filters: AuditLogFilterState,
  actorOptions: ActorOption[],
  onChange: (update: Partial<AuditLogFilterState>) => void
): Chip[] {
  const chips: Chip[] = []

  for (const moduleCode of filters.modules) {
    chips.push({
      key: `module-${moduleCode}`,
      label: `Module: ${AUDIT_MODULE_LABELS[moduleCode] ?? moduleCode}`,
      onRemove: () => {
        const modules = filters.modules.filter((m) => m !== moduleCode)
        onChange({
          modules,
          actions: validActionsForModules(modules, filters.actions),
        })
      },
    })
  }

  for (const action of filters.actions) {
    const label =
      AUDIT_ACTION_CATALOG.find((entry) => entry.action === action)?.label ??
      action
    chips.push({
      key: `action-${action}`,
      label: `Action: ${label}`,
      onRemove: () =>
        onChange({ actions: filters.actions.filter((a) => a !== action) }),
    })
  }

  for (const actorId of filters.actorIds) {
    const label =
      actorOptions.find((option) => option.id === actorId)?.label ?? actorId
    chips.push({
      key: `actor-${actorId}`,
      label: `Actor: ${label}`,
      onRemove: () =>
        onChange({ actorIds: filters.actorIds.filter((id) => id !== actorId) }),
    })
  }

  for (const objectType of filters.objectTypes) {
    chips.push({
      key: `objectType-${objectType}`,
      label: `Object type: ${objectType}`,
      onRemove: () =>
        onChange({
          objectTypes: filters.objectTypes.filter((t) => t !== objectType),
        }),
    })
  }

  if (filters.objectId.trim()) {
    chips.push({
      key: "objectId",
      label: `Object id: ${filters.objectId}`,
      onRemove: () => onChange({ objectId: "" }),
    })
  }
  if (filters.outcome) {
    chips.push({
      key: "outcome",
      label: `Outcome: ${OUTCOME_LABELS[filters.outcome]}`,
      onRemove: () => onChange({ outcome: "" }),
    })
  }
  if (filters.severity) {
    chips.push({
      key: "severity",
      label: `Severity: ${SEVERITY_LABELS[filters.severity]}`,
      onRemove: () => onChange({ severity: "" }),
    })
  }
  if (filters.occurredFrom) {
    chips.push({
      key: "occurredFrom",
      label: `From: ${formatDateTime(filters.occurredFrom)}`,
      onRemove: () => onChange({ occurredFrom: "" }),
    })
  }
  if (filters.occurredTo) {
    chips.push({
      key: "occurredTo",
      label: `To: ${formatDateTime(filters.occurredTo)}`,
      onRemove: () => onChange({ occurredTo: "" }),
    })
  }
  if (filters.correlationId) {
    chips.push({
      key: "correlationId",
      label: `Part of operation: ${filters.correlationId}`,
      onRemove: () => onChange({ correlationId: "" }),
    })
  }

  return chips
}

function Field({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div>
      <Label className="mb-1.5">{label}</Label>
      {children}
    </div>
  )
}

interface AuditLogFiltersProps {
  filters: AuditLogFilterState
  onChange: (update: Partial<AuditLogFilterState>) => void
  actorOptions: ActorOption[]
  canFilterByActor: boolean
  canExport: boolean
  isExporting: boolean
  onExport: () => void
}

/**
 * Filters live behind a single "Filters" trigger (a form-like panel, not
 * a toolbar of a dozen individually-wrapping controls - the earlier
 * inline-row layout, however arranged, always either overflowed
 * unpredictably or ate a lot of vertical space). Panel edits are staged
 * in local `draft` state and only reach the caller's `onChange` (and so
 * the actual search) on Apply - editing 9 fields shouldn't re-query after
 * every single keystroke/click. Applied filters render as removable
 * chips below the toolbar so the current search stays legible without
 * reopening the panel, each backed by `onChange` directly (no
 * Apply needed to remove one).
 */
export function AuditLogFilters({
  filters,
  onChange,
  actorOptions,
  canFilterByActor,
  canExport,
  isExporting,
  onExport,
}: AuditLogFiltersProps) {
  const [panelOpen, setPanelOpen] = useState(false)
  const [draft, setDraft] = useState(filters)
  const containerRef = useRef<HTMLDivElement>(null)

  // Re-seed the draft from the currently-applied filters every time the
  // panel opens, so a previous edit that was closed without Apply doesn't
  // linger into the next time it's opened (or while it's open, if the
  // applied filters change underneath it). Adjusted during render against
  // the last-seen panelOpen/filters rather than in an effect.
  const [seededFrom, setSeededFrom] = useState({ panelOpen, filters })
  if (panelOpen !== seededFrom.panelOpen || filters !== seededFrom.filters) {
    setSeededFrom({ panelOpen, filters })
    if (panelOpen) setDraft(filters)
  }

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setPanelOpen(false)
      }
    }
    document.addEventListener("click", handleClickOutside)
    return () => document.removeEventListener("click", handleClickOutside)
  }, [])

  function updateDraft(update: Partial<AuditLogFilterState>) {
    setDraft((current) => ({ ...current, ...update }))
  }

  function handleDraftModulesChange(modules: string[]) {
    updateDraft({
      modules,
      actions: validActionsForModules(modules, draft.actions),
    })
  }

  function applyDraft() {
    onChange(draft)
    setPanelOpen(false)
  }

  const activeCount = countActive(filters)
  const hasActiveFilters = activeCount > 0
  const chips = buildChips(filters, actorOptions, onChange)
  const draftActionOptions = auditActionOptionsForModules(draft.modules)

  return (
    <div className="mb-4">
      <div className="flex flex-wrap items-center gap-2.5">
        <div ref={containerRef} className="relative">
          <button
            type="button"
            onClick={() => setPanelOpen((v) => !v)}
            className={cn(
              "flex h-[38px] items-center gap-1.5 rounded-btn border bg-card px-3.5 text-[13px] font-semibold text-foreground transition-colors",
              panelOpen
                ? "border-primary ring-3 ring-primary/15"
                : "border-[var(--border-strong)] hover:border-primary hover:bg-muted hover:text-primary"
            )}
          >
            <SlidersHorizontal size={15} />
            Filters
            {activeCount > 0 && (
              <span className="flex size-[18px] items-center justify-center rounded-full bg-primary text-[11px] font-bold text-primary-foreground">
                {activeCount}
              </span>
            )}
            <ChevronDown size={14} className="text-[var(--ink-faint)]" />
          </button>

          {panelOpen && (
            // No onClick={stopPropagation}: the outside-click handler
            // already tests containerRef.contains(), and this panel is
            // inside it.
            <div className="absolute top-[calc(100%+6px)] left-[10px] z-20 w-[600px] max-w-[calc(100vw-60px)] rounded-md border border-border bg-card p-4 shadow-[var(--ledger-shadow)]">
              <div className="grid max-h-[65vh] grid-cols-1 gap-3 sm:grid-cols-2 overflow-y-auto pr-1">
                <Field label="Module">
                  <MultiSelect
                    allLabel="All modules"
                    searchPlaceholder="Search modules…"
                    selected={draft.modules}
                    onChange={handleDraftModulesChange}
                    options={auditModuleOptions().map((module) => ({
                      value: module,
                      label: AUDIT_MODULE_LABELS[module] ?? module,
                    }))}
                  />
                </Field>

                <Field label="Action">
                  <MultiSelect
                    allLabel="All actions"
                    searchPlaceholder="Search actions…"
                    selected={draft.actions}
                    onChange={(actions) => updateDraft({ actions })}
                    options={draftActionOptions.map((entry) => ({
                      value: entry.action,
                      label: entry.label,
                    }))}
                  />
                </Field>

                <Field label="Actor">
                  {canFilterByActor ? (
                    <ActorFilterSelect
                      options={actorOptions}
                      selected={draft.actorIds}
                      onChange={(actorIds) => updateDraft({ actorIds })}
                    />
                  ) : (
                    <input
                      type="text"
                      value={draft.actorIds[0] ?? ""}
                      onChange={(event) =>
                        updateDraft({
                          actorIds: event.target.value
                            ? [event.target.value]
                            : [],
                        })
                      }
                      placeholder="Actor id (UUID)…"
                      className="h-[38px] w-full rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
                    />
                  )}
                </Field>

                <Field label="Object type">
                  <MultiSelect
                    allLabel="All object types"
                    searchPlaceholder="Search object types…"
                    selected={draft.objectTypes}
                    onChange={(objectTypes) => updateDraft({ objectTypes })}
                    options={OBJECT_TYPE_OPTIONS.map((type) => ({
                      value: type,
                      label: type,
                    }))}
                  />
                </Field>

                <Field label="Object id">
                  <input
                    type="text"
                    value={draft.objectId}
                    onChange={(event) =>
                      updateDraft({ objectId: event.target.value })
                    }
                    placeholder="UUID…"
                    className="h-[38px] w-full rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary focus-visible:bg-card"
                  />
                </Field>

                <Field label="Outcome">
                  <SearchableSelect
                    allLabel="All outcomes"
                    searchable={false}
                    selected={draft.outcome}
                    onChange={(outcome) =>
                      updateDraft({
                        outcome: outcome as AuditLogFilterState["outcome"],
                      })
                    }
                    options={OUTCOME_OPTIONS}
                  />
                </Field>

                <Field label="Severity">
                  <SearchableSelect
                    allLabel="All severities"
                    searchable={false}
                    selected={draft.severity}
                    onChange={(severity) =>
                      updateDraft({
                        severity: severity as AuditLogFilterState["severity"],
                      })
                    }
                    options={SEVERITY_OPTIONS}
                  />
                </Field>

                <Field label="From">
                  <DateTimePicker
                    value={draft.occurredFrom}
                    onChange={(occurredFrom) => updateDraft({ occurredFrom })}
                    placeholder="Any time"
                  />
                </Field>

                <Field label="To">
                  <DateTimePicker
                    value={draft.occurredTo}
                    onChange={(occurredTo) => updateDraft({ occurredTo })}
                    placeholder="Any time"
                  />
                </Field>
              </div>

              <div className="mt-3.5 flex items-center justify-between border-t border-border pt-3.5">
                <button
                  type="button"
                  onClick={() => setDraft(EMPTY_AUDIT_LOG_FILTERS)}
                  className="text-[13px] font-semibold text-muted-foreground hover:text-foreground"
                >
                  Clear
                </button>
                <button
                  type="button"
                  onClick={applyDraft}
                  className="rounded-btn border border-primary bg-primary text-primary-foreground h-[38px] px-4 text-[13px] font-semibold inline-flex items-center justify-center"
                >
                  Apply
                </button>
              </div>
            </div>
          )}
        </div>

        {canExport && (
          <button
            type="button"
            onClick={onExport}
            disabled={isExporting}
            className="flex h-[38px] items-center gap-1.5 rounded-btn border border-[var(--border-strong)] bg-card px-3.5 text-[13px] font-semibold text-foreground transition-colors hover:border-primary hover:bg-muted hover:text-primary disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Download size={15} />
            {isExporting ? "Exporting…" : "Export CSV"}
          </button>
        )}
      </div>

      {hasActiveFilters && (
        <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
          {chips.map((chip) => (
            <span
              key={chip.key}
              className="flex items-center gap-1.5 rounded-btn border border-border bg-muted py-1 pr-1.5 pl-2.5 text-[12.5px] text-foreground"
            >
              {chip.label}
              <button
                type="button"
                onClick={chip.onRemove}
                aria-label={`Remove ${chip.label}`}
                className="flex size-4 items-center justify-center rounded-full text-[var(--ink-faint)] hover:bg-border hover:text-foreground"
              >
                <X size={11} />
              </button>
            </span>
          ))}

          <button
            type="button"
            onClick={() => onChange(EMPTY_AUDIT_LOG_FILTERS)}
            className="text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
          >
            Clear all
          </button>
        </div>
      )}
    </div>
  )
}
