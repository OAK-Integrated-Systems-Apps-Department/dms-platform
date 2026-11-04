"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Building2,
  Flame,
  Loader2,
  Pause,
  Pencil,
  Play,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { FriendlyDate } from "@/components/documents/browser-items"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/ui/loading-state"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Textarea } from "@/components/ui/textarea"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import {
  deactivateOrganisation,
  destroyOrganisationKeys,
  fetchOrganisations,
  provisionOrganisation,
  reactivateOrganisation,
  setOrganisationStatus,
  updateOrganisation,
  type OrganisationDto,
} from "@/lib/tenants/admin-api"

const KEY = ["platform", "organisations"] as const

type Pending = {
  kind: "suspend" | "resume" | "deactivate" | "reactivate"
  org: OrganisationDto
} | null

/** What an organisation is now, in one word. */
function stateOf(org: OrganisationDto): {
  label: string
  variant: "default" | "muted" | "gold" | "rust"
} {
  if (org.keysShreddedAt) return { label: "Keys destroyed", variant: "rust" }
  if (org.deactivated) return { label: "Deactivated", variant: "muted" }
  if (org.status === "suspended") return { label: "Suspended", variant: "gold" }
  if (org.status === "archived") return { label: "Closed", variant: "muted" }
  return { label: "Active", variant: "default" }
}

/**
 * Every organisation on the platform (platform admins only): suspend or
 * resume one (its users are signed out at once while suspended), deactivate
 * it, bring a deactivated one back, or destroy a deactivated one's
 * encryption keys so its documents can never be read again.
 */
export function Organisations() {
  const queryClient = useQueryClient()
  const orgs = useQuery({ queryKey: KEY, queryFn: fetchOrganisations })
  const [pending, setPending] = useState<Pending>(null)
  const [shredding, setShredding] = useState<OrganisationDto | null>(null)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<OrganisationDto | null>(null)

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: KEY })
  }

  if (orgs.isPending) return <LoadingState message="Loading organisations…" />
  if (orgs.isError) {
    return (
      <LoadFailed error={orgs.error} fallback="Could not load the organisations." />
    )
  }

  const copy = pending ? PENDING_COPY[pending.kind](pending.org) : null

  return (
    <div className="flex max-w-[1100px] flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-[12.5px] text-muted-foreground">
          {orgs.data.length}{" "}
          {orgs.data.length === 1 ? "organisation" : "organisations"}
        </p>
        <Button size="sm" onClick={() => setAdding(true)}>
          <Plus size={14} aria-hidden="true" /> Add organisation
        </Button>
      </div>
      <div className="overflow-x-auto rounded-xl border border-border bg-card">
        <table className="w-full min-w-[820px] text-[13px]">
          <thead className="border-b border-border bg-muted/30 text-left text-[11.5px] tracking-[0.04em] text-muted-foreground uppercase">
            <tr>
              <th className="px-4 py-2.5 font-semibold">Organisation</th>
              <th className="px-4 py-2.5 font-semibold">State</th>
              <th className="px-4 py-2.5 font-semibold">Plan</th>
              <th className="px-4 py-2.5 font-semibold">Created</th>
              <th className="px-4 py-2.5" />
            </tr>
          </thead>
          <tbody>
            {orgs.data.map((org) => {
              const state = stateOf(org)
              return (
                <tr
                  key={org.tenantId}
                  className="border-b border-border last:border-0"
                >
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2.5">
                      <Building2 size={16} className="shrink-0 text-primary" />
                      <div className="min-w-0">
                        <p className="truncate font-medium">{org.tenantName}</p>
                        <p className="truncate text-[12px] text-muted-foreground">
                          {org.domain}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <Badge variant={state.variant} className="rounded-md">
                      {state.label}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground capitalize">
                    {org.subscriptionTier}
                  </td>
                  <td className="px-4 py-3">
                    <FriendlyDate iso={org.createdAt} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      {/* Renaming a deactivated organisation would be
                          confusing rather than useful - bring it back first. */}
                      {!org.deactivated && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setEditing(org)}
                        >
                          <Pencil /> Edit
                        </Button>
                      )}
                      {!org.deactivated && org.status === "active" && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setPending({ kind: "suspend", org })}
                        >
                          <Pause /> Suspend
                        </Button>
                      )}
                      {!org.deactivated && org.status !== "active" && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setPending({ kind: "resume", org })}
                        >
                          <Play /> Resume
                        </Button>
                      )}
                      {!org.deactivated && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-[var(--rust)]"
                          onClick={() =>
                            setPending({ kind: "deactivate", org })
                          }
                        >
                          <Trash2 /> Deactivate
                        </Button>
                      )}
                      {org.deactivated && !org.keysShreddedAt && (
                        <>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              setPending({ kind: "reactivate", org })
                            }
                          >
                            <RotateCcw /> Reactivate
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-[var(--rust)]"
                            onClick={() => setShredding(org)}
                          >
                            <Flame /> Destroy keys
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <ConfirmActionDialog
        open={pending !== null}
        onOpenChange={(open) => !open && setPending(null)}
        title={copy?.title ?? ""}
        description={copy?.description ?? ""}
        confirmLabel={copy?.confirm ?? ""}
        pendingLabel="Working…"
        onConfirm={async () => {
          if (!pending) return null
          try {
            const { org, kind } = pending
            if (kind === "suspend")
              await setOrganisationStatus(org, "suspended")
            else if (kind === "resume")
              await setOrganisationStatus(org, "active")
            else if (kind === "deactivate")
              await deactivateOrganisation(org.tenantId)
            else await reactivateOrganisation(org.tenantId)
            toast.success(copy?.done ?? "Done.")
            refresh()
            return null
          } catch (error) {
            return friendlyMessage(error, "That didn't work.")
          }
        }}
      />

      {shredding && (
        <DestroyKeysSheet
          org={shredding}
          onOpenChange={(open) => !open && setShredding(null)}
          onDone={refresh}
        />
      )}

      {adding && (
        <AddOrganisationSheet
          onOpenChange={(open) => !open && setAdding(false)}
          onDone={refresh}
        />
      )}

      {editing && (
        <EditOrganisationSheet
          org={editing}
          onOpenChange={(open) => !open && setEditing(null)}
          onDone={refresh}
        />
      )}
    </div>
  )
}

/**
 * Onboards a client: the organisation and its first administrator together,
 * via `POST /api/v1/tenants/provision`. Both at once because an organisation
 * created on its own has nobody who can log into it, and no other endpoint
 * can create a user inside it afterwards.
 */
function AddOrganisationSheet({
  onOpenChange,
  onDone,
}: {
  onOpenChange: (open: boolean) => void
  onDone: () => void
}) {
  const [form, setForm] = useState({
    tenantName: "",
    domain: "",
    subscriptionTier: "basic",
    adminEmail: "",
    adminUsername: "",
    adminFirstName: "",
    adminLastName: "",
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const set = (field: keyof typeof form) => (value: string) =>
    setForm((current) => ({ ...current, [field]: value }))

  // Mirrors the backend's own @Pattern on domain and adminUsername, so a
  // typo is caught here rather than coming back as a 400 field error.
  const domainOk =
    /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/.test(
      form.domain
    )
  const usernameOk = /^[a-zA-Z0-9._-]+$/.test(form.adminUsername)
  const complete =
    form.tenantName.trim() !== "" &&
    form.domain.trim() !== "" &&
    domainOk &&
    form.subscriptionTier.trim() !== "" &&
    form.adminEmail.trim() !== "" &&
    form.adminUsername.trim() !== "" &&
    usernameOk &&
    form.adminFirstName.trim() !== "" &&
    form.adminUsername.length >= 3

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const result = await provisionOrganisation({
        ...form,
        adminLastName: form.adminLastName.trim() || undefined,
      })
      toast.success(
        `${result.tenant.tenantName} created. ${result.admin.email} can set their password from the email just sent.`
      )
      onDone()
      onOpenChange(false)
    } catch (e) {
      setError(friendlyMessage(e, "Could not create the organisation."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-[520px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>Add organisation</SheetTitle>
          <SheetDescription>
            Creates the organisation and its first administrator together. They
            are emailed a link to set their own password — no password is set
            here.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-4 overflow-y-auto px-4 py-4">
          <Field
            label="Organisation name"
            value={form.tenantName}
            onChange={set("tenantName")}
            placeholder="Acme Ltd"
          />
          <Field
            label="Domain"
            value={form.domain}
            onChange={(v) => set("domain")(v.toLowerCase())}
            placeholder="acme.oakdms.com"
            hint="Lowercase hostname. Doubles as the organisation's login domain and can't be changed later."
            error={
              form.domain !== "" && !domainOk
                ? "Must be a lowercase hostname, e.g. acme.oakdms.com"
                : null
            }
          />
          <Field
            label="Plan"
            value={form.subscriptionTier}
            onChange={set("subscriptionTier")}
            placeholder="basic"
            hint="Free text — whatever your plans are called."
          />

          <div className="mt-1 border-t border-border pt-4">
            <p className="mb-3 text-[12.5px] font-semibold text-foreground">
              First administrator
            </p>
            <div className="flex flex-col gap-4">
              <Field
                label="Email"
                type="email"
                value={form.adminEmail}
                onChange={set("adminEmail")}
                placeholder="admin@acme.com"
              />
              <Field
                label="Username"
                value={form.adminUsername}
                onChange={set("adminUsername")}
                placeholder="acme.admin"
                error={
                  form.adminUsername !== "" && !usernameOk
                    ? "Letters, numbers, dots, hyphens and underscores only"
                    : null
                }
              />
              <div className="grid grid-cols-2 gap-3">
                <Field
                  label="First name"
                  value={form.adminFirstName}
                  onChange={set("adminFirstName")}
                />
                <Field
                  label="Last name"
                  value={form.adminLastName}
                  onChange={set("adminLastName")}
                  optional
                />
              </div>
            </div>
          </div>

          {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
        </div>

        <SheetFooter className="border-t border-border">
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={saving}
            >
              Cancel
            </Button>
            <Button onClick={() => void save()} disabled={!complete || saving}>
              {saving && <Loader2 className="animate-spin" />}
              {saving ? "Creating…" : "Create organisation"}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/**
 * Rename an organisation or change its plan. Domain is deliberately not
 * editable - the backend's update request has no field for it, since it is
 * the login domain and changing it would lock the organisation's users out.
 */
function EditOrganisationSheet({
  org,
  onOpenChange,
  onDone,
}: {
  org: OrganisationDto
  onOpenChange: (open: boolean) => void
  onDone: () => void
}) {
  const [tenantName, setTenantName] = useState(org.tenantName)
  const [subscriptionTier, setSubscriptionTier] = useState(org.subscriptionTier)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const changed =
    tenantName.trim() !== org.tenantName ||
    subscriptionTier.trim() !== org.subscriptionTier
  const complete = tenantName.trim() !== "" && subscriptionTier.trim() !== ""

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await updateOrganisation(org, {
        tenantName: tenantName.trim(),
        subscriptionTier: subscriptionTier.trim(),
      })
      toast.success(`${tenantName.trim()} updated.`)
      onDone()
      onOpenChange(false)
    } catch (e) {
      setError(friendlyMessage(e, "Could not update the organisation."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-[480px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>Edit {org.tenantName}</SheetTitle>
          <SheetDescription>
            Suspending, deactivating and destroying keys stay on the list — this
            only changes the name and plan.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-4 overflow-y-auto px-4 py-4">
          <Field
            label="Organisation name"
            value={tenantName}
            onChange={setTenantName}
          />
          <Field
            label="Plan"
            value={subscriptionTier}
            onChange={setSubscriptionTier}
          />
          <div>
            <p className="mb-1 text-[12px] font-medium text-muted-foreground">
              Domain
            </p>
            <p className="font-mono text-[12.5px] text-foreground">
              {org.domain}
            </p>
            <p className="mt-1 text-[11.5px] text-muted-foreground">
              Can&apos;t be changed — it&apos;s the login domain, and changing
              it would lock this organisation&apos;s users out.
            </p>
          </div>
          {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
        </div>

        <SheetFooter className="border-t border-border">
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={saving}
            >
              Cancel
            </Button>
            <Button
              onClick={() => void save()}
              disabled={!changed || !complete || saving}
            >
              {saving && <Loader2 className="animate-spin" />}
              {saving ? "Saving…" : "Save changes"}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/** One labelled input - the two sheets above share the same field layout. */
function Field({
  label,
  value,
  onChange,
  placeholder,
  hint,
  error,
  optional,
  type = "text",
}: {
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  hint?: string
  error?: string | null
  optional?: boolean
  type?: string
}) {
  return (
    <div>
      <label className="mb-1 block text-[12px] font-medium text-foreground">
        {label}
        {optional && (
          <span className="ml-1 font-normal text-muted-foreground">
            (optional)
          </span>
        )}
      </label>
      <Input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className="h-9 text-[13px]"
      />
      {error ? (
        <p className="mt-1 text-[11.5px] text-[var(--rust)]">{error}</p>
      ) : hint ? (
        <p className="mt-1 text-[11.5px] text-muted-foreground">{hint}</p>
      ) : null}
    </div>
  )
}

const PENDING_COPY: Record<
  NonNullable<Pending>["kind"],
  (org: OrganisationDto) => {
    title: string
    description: string
    confirm: string
    done: string
  }
> = {
  suspend: (org) => ({
    title: `Suspend ${org.tenantName}?`,
    description:
      "Its users are signed out at once and can't sign in until it is resumed. Nothing is deleted.",
    confirm: "Suspend",
    done: `${org.tenantName} is suspended.`,
  }),
  resume: (org) => ({
    title: `Resume ${org.tenantName}?`,
    description: "Its users can sign in and work again.",
    confirm: "Resume",
    done: `${org.tenantName} is active again.`,
  }),
  deactivate: (org) => ({
    title: `Deactivate ${org.tenantName}?`,
    description:
      "It is removed: its users can't sign in, and it no longer counts as a customer. Its documents are kept until you destroy its keys - it can be reactivated until then.",
    confirm: "Deactivate",
    done: `${org.tenantName} was deactivated.`,
  }),
  reactivate: (org) => ({
    title: `Reactivate ${org.tenantName}?`,
    description: "It comes back as active, with all its users and documents.",
    confirm: "Reactivate",
    done: `${org.tenantName} is active again.`,
  }),
}

/** Crypto-shredding, with every safeguard the server asks for: the exact name, a reason, and the unencrypted-files warning. */
function DestroyKeysSheet({
  org,
  onOpenChange,
  onDone,
}: {
  org: OrganisationDto
  onOpenChange: (open: boolean) => void
  onDone: () => void
}) {
  const [name, setName] = useState("")
  const [reason, setReason] = useState("")
  const [acknowledge, setAcknowledge] = useState(false)
  const [unencryptedWarning, setUnencryptedWarning] = useState<string | null>(
    null
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ready =
    name.trim() === org.tenantName &&
    reason.trim().length > 0 &&
    (!unencryptedWarning || acknowledge)

  async function destroy() {
    setBusy(true)
    setError(null)
    try {
      const result = await destroyOrganisationKeys(
        org.tenantId,
        name.trim(),
        reason.trim(),
        acknowledge
      )
      toast.success(
        `${org.tenantName}'s keys were destroyed (${result.wrappedKeysDeleted} file keys).`
      )
      onDone()
      onOpenChange(false)
    } catch (e) {
      if (e instanceof ApiError && e.code === "UNENCRYPTED_FILES_LEFT") {
        setUnencryptedWarning(e.message)
      } else {
        setError(friendlyMessage(e, "Could not destroy the keys."))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 data-[side=right]:sm:max-w-[520px]"
      >
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2 text-[var(--rust)]">
            <Flame size={18} /> Destroy {org.tenantName}&apos;s keys
          </SheetTitle>
          <SheetDescription>
            Every document it stored encrypted becomes unreadable for good -
            here, in backups, everywhere. This can&apos;t be undone, and the
            organisation can never be reactivated.
          </SheetDescription>
        </SheetHeader>
        <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-5 text-[13px]">
          <label className="flex flex-col gap-1.5">
            <span className="font-medium">
              Type the organisation&apos;s name to confirm:{" "}
              <span className="font-mono">{org.tenantName}</span>
            </span>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={busy}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="font-medium">Why (kept in the audit log)</span>
            <Textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={1000}
              rows={3}
              disabled={busy}
            />
          </label>
          {unencryptedWarning && (
            <div className="flex flex-col gap-2 rounded-lg border border-[var(--gold)] bg-[var(--gold-tint)] p-3 text-[12.5px]">
              <p>{unencryptedWarning}</p>
              <label className="flex items-center gap-2 font-medium">
                <input
                  type="checkbox"
                  checked={acknowledge}
                  onChange={(e) => setAcknowledge(e.target.checked)}
                  disabled={busy}
                />
                I understand those files stay readable
              </label>
            </div>
          )}
          {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
        </div>
        <SheetFooter className="border-t border-border">
          <Button
            className="bg-[var(--rust)] text-white hover:bg-[var(--rust)]/90"
            disabled={!ready || busy}
            onClick={() => void destroy()}
          >
            {busy ? <Loader2 className="animate-spin" /> : <Flame />}
            Destroy keys for good
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
