"use client"

import { useState } from "react"
import { toast } from "sonner"
import { Loader2, Pencil, Plus, Trash2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadingState } from "@/components/ui/loading-state"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { retentionError } from "@/lib/retention/messages"
import { LoadFailed } from "@/components/ui/load-failed"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type {
  PolicyScope,
  RetentionPolicyDto,
  RetentionPolicyRequest,
  RetentionScopesDto,
  RetentionUnit,
} from "@/lib/retention/api"
import { describeRule, describeVersionLimits, SCOPE_LABELS } from "@/lib/retention/format"
import { usePolicyActions, useRetentionPolicies, useRetentionScopes } from "@/lib/retention/use-retention"

const SELECT =
  "h-10 w-full rounded-btn border border-[var(--border-strong)] bg-card px-2.5 text-[13px] text-foreground disabled:opacity-60"

/** Regulatory names offered as suggestions in the label field - a label only; the periods are the administrator's to set. */
const FRAMEWORK_SUGGESTIONS = ["GDPR", "HMRC", "Companies Act 2006", "Internal policy"]

type FormState = {
  name: string
  description: string
  scopeType: PolicyScope
  scopeId: string
  includeSubfolders: boolean
  activeAmount: string
  activeUnit: RetentionUnit
  archiveAmount: string
  archiveUnit: RetentionUnit
  clockStart: "created" | "last_modified"
  framework: string
  enabled: boolean
  /** Blank = no limit. */
  maxVersions: string
  versionAgeAmount: string
  versionAgeUnit: RetentionUnit
}

const EMPTY: FormState = {
  name: "",
  description: "",
  scopeType: "organisation",
  scopeId: "",
  includeSubfolders: true,
  activeAmount: "1",
  activeUnit: "years",
  archiveAmount: "6",
  archiveUnit: "years",
  clockStart: "created",
  framework: "",
  enabled: true,
  maxVersions: "",
  versionAgeAmount: "",
  versionAgeUnit: "years",
}

function toForm(rule: RetentionPolicyDto): FormState {
  return {
    name: rule.name,
    description: rule.description ?? "",
    scopeType: rule.scopeType,
    scopeId: rule.scopeId ?? "",
    includeSubfolders: rule.includeSubfolders,
    activeAmount: String(rule.activeAmount),
    activeUnit: rule.activeUnit,
    archiveAmount: String(rule.archiveAmount),
    archiveUnit: rule.archiveUnit,
    clockStart: rule.clockStart,
    framework: rule.framework ?? "",
    enabled: rule.enabled,
    maxVersions: rule.maxVersions == null ? "" : String(rule.maxVersions),
    versionAgeAmount: rule.versionAgeAmount == null ? "" : String(rule.versionAgeAmount),
    versionAgeUnit: rule.versionAgeUnit ?? "years",
  }
}

/** The form's values as the backend takes them, or the first thing wrong with them (shown beside the Save button). */
export function toRequest(form: FormState): { request: RetentionPolicyRequest } | { problem: string } {
  // Whole numbers only: parseInt would quietly turn "1.5" into 1.
  const whole = (text: string) => (/^-?[0-9]+$/.test(text.trim()) ? Number(text.trim()) : Number.NaN)
  const activeAmount = whole(form.activeAmount)
  const archiveAmount = form.archiveAmount.trim() === "" ? 0 : whole(form.archiveAmount)
  if (!form.name.trim()) return { problem: "Give the rule a name." }
  if (form.scopeType !== "organisation" && !form.scopeId) return { problem: `Choose which ${SCOPE_LABELS[form.scopeType].toLowerCase()} it covers.` }
  if (!Number.isInteger(activeAmount) || activeAmount < 1 || activeAmount > 1000) return { problem: "The active period must be a whole number from 1 to 1000." }
  if (!Number.isInteger(archiveAmount) || archiveAmount < 0 || archiveAmount > 1000) return { problem: "The archive period must be a whole number from 0 to 1000 (0 = none)." }
  // Blank = no limit; otherwise a whole number in range.
  const maxVersions = form.maxVersions.trim() === "" ? null : whole(form.maxVersions)
  const versionAgeAmount = form.versionAgeAmount.trim() === "" ? null : whole(form.versionAgeAmount)
  if (maxVersions !== null && (!Number.isInteger(maxVersions) || maxVersions < 1 || maxVersions > 10000))
    return { problem: "Keep between 1 and 10000 versions, or leave it blank for no limit." }
  if (versionAgeAmount !== null && (!Number.isInteger(versionAgeAmount) || versionAgeAmount < 1 || versionAgeAmount > 1000))
    return { problem: "Remove old versions after 1 to 1000 days, months or years, or leave it blank for no limit." }
  return {
    request: {
      name: form.name.trim(),
      description: form.description.trim() || null,
      scopeType: form.scopeType,
      scopeId: form.scopeType === "organisation" ? null : form.scopeId,
      includeSubfolders: form.includeSubfolders,
      activeAmount,
      activeUnit: form.activeUnit,
      archiveAmount,
      archiveUnit: form.archiveUnit,
      clockStart: form.clockStart,
      framework: form.framework.trim() || null,
      enabled: form.enabled,
      maxVersions,
      versionAgeAmount,
      versionAgeUnit: versionAgeAmount === null ? null : form.versionAgeUnit,
    },
  }
}

/** "Contracts / Signed" - a folder's path, from the flat list the backend sends. */
function folderPaths(scopes: RetentionScopesDto): { id: string; label: string }[] {
  const byId = new Map(scopes.folders.map((f) => [f.id, f]))
  const path = (id: string, seen = new Set<string>()): string => {
    const folder = byId.get(id)
    if (!folder || seen.has(id)) return ""
    seen.add(id)
    const parent = folder.parentId ? path(folder.parentId, seen) : ""
    return parent ? `${parent} / ${folder.name}` : folder.name
  }
  return scopes.folders.map((f) => ({ id: f.id, label: path(f.id) })).sort((a, b) => a.label.localeCompare(b.label))
}

/**
 * Retention rules (CRM-FR-006, 008, 010, 011): one per scope - the organisation default, a document type, a department
 * or a folder - each with its own active and archive periods, clock and regulatory label, all running side by side.
 */
export function RetentionPoliciesTab() {
  const { data, isPending, isError, error, refetch } = useRetentionPolicies()
  const { hasPermission } = usePermissions()
  const { create, update, remove } = usePolicyActions()
  const [editing, setEditing] = useState<RetentionPolicyDto | "new" | null>(null)
  const [deleting, setDeleting] = useState<RetentionPolicyDto | null>(null)
  const canCreate = hasPermission(PermissionCodes.CRM_POLICY_CREATE)
  const canUpdate = hasPermission(PermissionCodes.CRM_POLICY_UPDATE)
  const canDelete = hasPermission(PermissionCodes.CRM_POLICY_DELETE)

  if (isPending) return <LoadingState message="Loading retention rules…" />
  if (isError) return <LoadFailed error={error} fallback="Could not load the retention rules." onRetry={() => void refetch()} />

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
          A rule says how long documents stay <strong>Active</strong>, and how long they are then kept <strong>Archived</strong>
          , before an administrator decides what happens next. Nothing is ever deleted automatically. When several rules
          cover one document, the winner is chosen by the precedence on the Settings tab.
        </p>
        {canCreate && (
          <Button type="button" onClick={() => setEditing("new")}>
            <Plus /> New rule
          </Button>
        )}
      </div>

      {data.length === 0 ? (
        <p className="flex min-h-[160px] flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-10 text-center text-[13px] text-muted-foreground">
          No retention rules yet. Until you add one, nothing is archived and no document is flagged.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border bg-card">
          {data.map((rule) => (
            <li key={rule.policyId} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[13.5px] font-semibold">{rule.name}</span>
                  {rule.framework && <Badge variant="outline">{rule.framework}</Badge>}
                  {!rule.enabled && <Badge variant="muted">Off</Badge>}
                </div>
                <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                  {SCOPE_LABELS[rule.scopeType]}
                  {rule.scopeType !== "organisation" ? `: ${rule.scopeName}` : ""} · {describeRule(rule)} · counted from{" "}
                  {rule.clockStart === "created" ? "creation" : "the last change"}
                </p>
                {describeVersionLimits(rule) && (
                  <p className="mt-0.5 text-[12.5px] text-muted-foreground">Old versions: {describeVersionLimits(rule)}</p>
                )}
              </div>
              {canUpdate && (
                <button
                  type="button"
                  aria-label={`Edit ${rule.name}`}
                  onClick={() => setEditing(rule)}
                  className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-foreground size-8 rounded-btn"
                >
                  <Pencil size={15} />
                </button>
              )}
              {canDelete && (
                <button
                  type="button"
                  aria-label={`Delete ${rule.name}`}
                  onClick={() => setDeleting(rule)}
                  className="flex items-center justify-center text-muted-foreground hover:bg-muted hover:text-[var(--rust)] size-8 rounded-btn"
                >
                  <Trash2 size={15} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <PolicyForm
          key={editing === "new" ? "new" : editing.policyId}
          rule={editing === "new" ? null : editing}
          saving={create.isPending || update.isPending}
          onClose={() => setEditing(null)}
          onSave={async (request) => {
            if (editing === "new") await create.mutateAsync(request)
            else await update.mutateAsync({ policyId: editing.policyId, request })
            setEditing(null)
          }}
        />
      )}

      <ConfirmActionDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Delete "${deleting?.name ?? ""}"?`}
        description="Documents this rule covered fall back to another rule that covers them - or are flagged for an administrator if none does. Nothing already archived is brought back."
        confirmLabel="Delete rule"
        pendingLabel="Deleting…"
        onConfirm={async () => {
          if (!deleting) return null
          try {
            await remove.mutateAsync(deleting.policyId)
            return null
          } catch (e) {
            return retentionError(e, "Could not delete the rule.", { action: "delete retention rules", needs: "Delete retention policy" })
          }
        }}
      />
    </div>
  )
}

function PolicyForm({
  rule,
  saving,
  onClose,
  onSave,
}: {
  rule: RetentionPolicyDto | null
  saving: boolean
  onClose: () => void
  onSave: (request: RetentionPolicyRequest) => Promise<void>
}) {
  const [form, setForm] = useState<FormState>(rule ? toForm(rule) : EMPTY)
  const { hasPermission } = usePermissions()
  // The limits on old versions are their own permissions (the maximum and the age are separate) - the server enforces them.
  const canSetMax = hasPermission(PermissionCodes.DVC_SET_MAX_VERSIONS)
  const canSetAge = hasPermission(PermissionCodes.DVC_SET_VERSION_PERIOD)
  const { data: scopes } = useRetentionScopes(true)
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }))

  const options: { id: string; label: string }[] =
    !scopes || form.scopeType === "organisation"
      ? []
      : form.scopeType === "document_type"
        ? scopes.documentTypes.map((t) => ({ id: t.id, label: t.name }))
        : form.scopeType === "department"
          ? scopes.departments.map((d) => ({ id: d.id, label: d.name }))
          : folderPaths(scopes)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const result = toRequest(form)
    if ("problem" in result) {
      toast.error(result.problem, { id: "retention-rule-invalid" })
      return
    }
    try {
      await onSave(result.request)
    } catch {
      // The mutation already toasted the server's message (for example a second rule for the same scope).
    }
  }

  return (
    <Sheet open onOpenChange={(open) => !open && !saving && onClose()}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[560px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>{rule ? "Edit retention rule" : "New retention rule"}</SheetTitle>
          <SheetDescription>
            How long documents in its scope stay Active and then Archived. It applies the next time retention runs.
          </SheetDescription>
        </SheetHeader>
        <form onSubmit={submit} className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
          <div>
            <Label htmlFor="rule-name">Name</Label>
            <Input id="rule-name" value={form.name} maxLength={200} onChange={(e) => set("name", e.target.value)} />
          </div>

          <div>
            <Label htmlFor="rule-scope">Applies to</Label>
            <select
              id="rule-scope"
              className={SELECT}
              value={form.scopeType}
              onChange={(e) => setForm((f) => ({ ...f, scopeType: e.target.value as PolicyScope, scopeId: "" }))}
            >
              {(Object.keys(SCOPE_LABELS) as PolicyScope[]).map((scope) => (
                <option key={scope} value={scope}>
                  {SCOPE_LABELS[scope]}
                  {scope === "organisation" ? " (the default for everything)" : ""}
                </option>
              ))}
            </select>
          </div>

          {form.scopeType !== "organisation" && (
            <div>
              <Label htmlFor="rule-target">{SCOPE_LABELS[form.scopeType]}</Label>
              <select id="rule-target" className={SELECT} value={form.scopeId} onChange={(e) => set("scopeId", e.target.value)}>
                <option value="">Choose…</option>
                {options.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          )}

          {form.scopeType === "folder" && (
            <label className="flex items-center gap-2.5 text-[13px]">
              <Switch checked={form.includeSubfolders} onCheckedChange={(v) => set("includeSubfolders", v)} aria-label="Include subfolders" />
              Also cover the folders inside it
            </label>
          )}

          <fieldset className="grid grid-cols-[1fr_auto] items-end gap-x-2 gap-y-3">
            <legend className="mb-1 text-[13px] font-semibold">Retention periods</legend>
            <div>
              <Label htmlFor="rule-active">Active for</Label>
              <Input id="rule-active" type="number" min={1} max={1000} value={form.activeAmount} onChange={(e) => set("activeAmount", e.target.value)} />
            </div>
            <UnitSelect label="Active period unit" value={form.activeUnit} onChange={(v) => set("activeUnit", v)} />
            <div>
              <Label htmlFor="rule-archive">Then archived for</Label>
              <Input id="rule-archive" type="number" min={0} max={1000} value={form.archiveAmount} onChange={(e) => set("archiveAmount", e.target.value)} />
            </div>
            <UnitSelect label="Archive period unit" value={form.archiveUnit} onChange={(v) => set("archiveUnit", v)} />
            <p className="col-span-2 text-[12px] text-muted-foreground">
              After the archive period an administrator reviews the document - nothing is deleted automatically. Use 0 for no
              archive period.
            </p>
          </fieldset>

          <fieldset className="grid grid-cols-[1fr_auto] items-end gap-x-2 gap-y-3 border-t border-border pt-4">
            <legend className="mb-1 text-[13px] font-semibold">Old versions (optional)</legend>
            <div className="col-span-2">
              <Label htmlFor="rule-max-versions">Keep at most this many versions of a document</Label>
              <Input id="rule-max-versions" type="number" min={1} max={10000} value={form.maxVersions} disabled={!canSetMax}
                placeholder="No limit" onChange={(e) => set("maxVersions", e.target.value)} />
            </div>
            <div>
              <Label htmlFor="rule-version-age">Remove superseded versions older than</Label>
              <Input id="rule-version-age" type="number" min={1} max={1000} value={form.versionAgeAmount} disabled={!canSetAge}
                placeholder="No limit" onChange={(e) => set("versionAgeAmount", e.target.value)} />
            </div>
            <UnitSelect label="Version age unit" value={form.versionAgeUnit} onChange={(v) => set("versionAgeUnit", v)} disabled={!canSetAge} />
            <p className="col-span-2 text-[12px] text-muted-foreground">
              A removed version is hidden first and its files are destroyed only after the grace period set on the Settings tab
              - an administrator can restore it until then. The current and published versions, protected, signed or approved
              versions, versions linked from other documents, and anything on legal hold are never removed.
              {(!canSetMax || !canSetAge) && " Setting these needs the version-retention permissions."}
            </p>
          </fieldset>

          <div>
            <Label htmlFor="rule-clock">Count from</Label>
            <select id="rule-clock" className={SELECT} value={form.clockStart} onChange={(e) => set("clockStart", e.target.value as FormState["clockStart"])}>
              <option value="created">When the document was created</option>
              <option value="last_modified">When the document was last changed</option>
            </select>
          </div>

          <div>
            <Label htmlFor="rule-framework">Regulation or policy (a label)</Label>
            <Input id="rule-framework" list="rule-frameworks" value={form.framework} maxLength={120} onChange={(e) => set("framework", e.target.value)} placeholder="e.g. HMRC" />
            <datalist id="rule-frameworks">
              {FRAMEWORK_SUGGESTIONS.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
            <p className="mt-1 text-[12px] text-muted-foreground">
              Shown next to the rule and recorded in the audit trail. The periods above are yours to set - check them against
              what your obligations require.
            </p>
          </div>

          <div>
            <Label htmlFor="rule-notes">Notes</Label>
            <Textarea id="rule-notes" rows={2} value={form.description} onChange={(e) => set("description", e.target.value)} />
          </div>

          <label className="flex items-center gap-2.5 text-[13px]">
            <Switch checked={form.enabled} onCheckedChange={(v) => set("enabled", v)} aria-label="Rule is on" />
            {form.enabled ? "On" : "Off - ignored until switched on"}
          </label>

          <div className="mt-auto flex gap-2 border-t border-border pt-4">
            <Button type="submit" disabled={saving}>
              {saving && <Loader2 className="animate-spin" />}
              {rule ? "Save rule" : "Create rule"}
            </Button>
            <Button type="button" variant="ghost" disabled={saving} onClick={onClose}>
              Cancel
            </Button>
          </div>
        </form>
      </SheetContent>
    </Sheet>
  )
}

function UnitSelect({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string
  value: RetentionUnit
  onChange: (value: RetentionUnit) => void
  disabled?: boolean
}) {
  return (
    <select aria-label={label} className={`${SELECT} w-[110px]`} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as RetentionUnit)}>
      <option value="days">days</option>
      <option value="months">months</option>
      <option value="years">years</option>
    </select>
  )
}
