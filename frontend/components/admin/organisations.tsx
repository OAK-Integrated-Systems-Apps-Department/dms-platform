"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useId, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Building2,
  Flame,
  Loader2,
  Mail,
  Pause,
  Pencil,
  Play,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react"
import { toast } from "sonner"

import {
  ConfirmPasswordFields,
  usePasswordConfirmation,
} from "@/components/admin/confirm-password"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { FriendlyDate } from "@/components/documents/browser-items"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Switch } from "@/components/ui/switch"
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
  fetchOnlineEditingAllowed,
  setOnlineEditingAllowed,
  destroyOrganisationKeys,
  fetchOrganisations,
  fetchTenantAdmins,
  provisionOrganisation,
  resendTenantAdminInvite,
  reactivateOrganisation,
  setOrganisationStatus,
  updateOrganisation,
  updateTenantAdmin,
  type OrganisationDto,
  type TenantAdminDto,
} from "@/lib/tenants/admin-api"

const KEY = ["platform", "organisations"] as const
const EDITING_KEY = ["platform", "organisations-online-editing"] as const
const ADMINS_KEY = ["platform", "organisation-admins"] as const

type Pending = {
  kind:
    | "suspend"
    | "resume"
    | "deactivate"
    | "reactivate"
    | "editing-on"
    | "editing-off"
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
  // Which organisations the platform has allowed online editing - one call for the whole list.
  const editingAllowed = useQuery({
    queryKey: EDITING_KEY,
    queryFn: fetchOnlineEditingAllowed,
  })
  const [pending, setPending] = useState<Pending>(null)
  const [shredding, setShredding] = useState<OrganisationDto | null>(null)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<OrganisationDto | null>(null)

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: KEY })
    void queryClient.invalidateQueries({ queryKey: EDITING_KEY })
  }

  if (orgs.isPending) return <LoadingState message="Loading organisations…" />
  if (orgs.isError) {
    return (
      <LoadFailed error={orgs.error} fallback="Could not load the organisations." />
    )
  }

  const copy = pending ? PENDING_COPY[pending.kind](pending.org) : null

  return (
    <div className="flex w-full flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-[12.5px] text-muted-foreground">
          {orgs.data.length}{" "}
          {orgs.data.length === 1 ? "organisation" : "organisations"}
        </p>
        <Button size="sm" onClick={() => setAdding(true)}>
          <Plus size={14} aria-hidden="true" /> Add organisation
        </Button>
      </div>
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="tbl-card w-full min-w-[1080px] text-[13px]">
          <thead className="border-b border-border bg-muted/30 text-left text-[11.5px] tracking-[0.04em] text-muted-foreground uppercase">
            <tr>
              <th className="px-4 py-2.5 font-semibold">Organisation</th>
              <th className="px-4 py-2.5 font-semibold">Administrator</th>
              <th className="px-4 py-2.5 font-semibold">State</th>
              <th className="px-4 py-2.5 font-semibold">Plan</th>
              <th className="px-4 py-2.5 font-semibold">Online editing</th>
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
                  <td className="max-w-[240px] px-4 py-3">
                    <AdminCell org={org} onOpen={() => setEditing(org)} />
                  </td>
                  <td className="px-4 py-3">
                    <Badge variant={state.variant} className="rounded-btn">
                      {state.label}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground capitalize">
                    {org.subscriptionTier}
                  </td>
                  <td className="px-4 py-3">
                    {/* Allowed or not by the platform; its own administrator then decides for its people. The switch only
                        asks - it moves when the server has changed it, after the platform administrator's password. */}
                    <label className="flex w-max items-center gap-2 text-[12.5px] font-medium">
                      <Switch
                        checked={editingAllowed.data?.includes(org.tenantId) === true}
                        disabled={
                          org.deactivated ||
                          editingAllowed.isPending ||
                          editingAllowed.isError
                        }
                        onCheckedChange={(on) =>
                          setPending({
                            kind: on ? "editing-on" : "editing-off",
                            org,
                          })
                        }
                        aria-label={`Online editing for ${org.tenantName}`}
                      />
                      {editingAllowed.data?.includes(org.tenantId) ? "Allowed" : "Off"}
                    </label>
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

      {pending && copy && (
        <PendingActionDialog
          pending={pending}
          copy={copy}
          onClose={() => setPending(null)}
          onDone={refresh}
        />
      )}

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

/**
 * Suspend, resume, deactivate or bring back an organisation. All but bringing one back change what its people can
 * do, so they ask for the signed-in platform administrator's own password first; the dialog stays open on a wrong one.
 */
function PendingActionDialog({
  pending,
  copy,
  onClose,
  onDone,
}: {
  pending: NonNullable<Pending>
  copy: { title: string; description: string; confirm: string; done: string }
  onClose: () => void
  onDone: () => void
}) {
  const confirm = usePasswordConfirmation()
  const needsPassword = pending.kind !== "reactivate"
  return (
    <ConfirmActionDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={copy.title}
      description={copy.description}
      confirmLabel={copy.confirm}
      pendingLabel="Working…"
      confirmDisabled={needsPassword && !confirm.ready}
      onConfirm={async () => {
        try {
          const { org, kind } = pending
          if (kind === "suspend")
            await setOrganisationStatus(confirm.value, org, "suspended")
          else if (kind === "resume")
            await setOrganisationStatus(confirm.value, org, "active")
          else if (kind === "deactivate")
            await deactivateOrganisation(confirm.value, org.tenantId)
          else if (kind === "editing-on")
            await setOnlineEditingAllowed(confirm.value, org.tenantId, true)
          else if (kind === "editing-off")
            await setOnlineEditingAllowed(confirm.value, org.tenantId, false)
          else await reactivateOrganisation(org.tenantId)
          toast.success(copy.done)
          onDone()
          return null
        } catch (error) {
          confirm.noteError(error)
          return friendlyMessage(error, "That didn't work.")
        }
      }}
    >
      {needsPassword && <ConfirmPasswordFields confirm={confirm} />}
    </ConfirmActionDialog>
  )
}

/**
 * The organisation's administrator, right in the list: who they are and whether they have accepted
 * the invitation. Click it to see or correct their details.
 */
function AdminCell({
  org,
  onOpen,
}: {
  org: OrganisationDto
  onOpen: () => void
}) {
  const admins = useQuery({
    queryKey: [...ADMINS_KEY, org.tenantId],
    queryFn: () => fetchTenantAdmins(org.tenantId),
    staleTime: 30_000,
    retry: false,
  })
  if (admins.isPending) {
    return <span className="text-[12px] text-muted-foreground">Loading…</span>
  }
  if (admins.isError || admins.data.length === 0) {
    return <span className="text-[12px] text-muted-foreground">—</span>
  }
  const [first, ...rest] = admins.data
  const body = (
    <>
      <span className="flex items-center gap-1.5">
        <span className="truncate font-medium">{first.displayName}</span>
        {!first.verified && (
          <Badge variant="gold" className="shrink-0">
            Invited
          </Badge>
        )}
        {rest.length > 0 && (
          <span className="shrink-0 text-[12px] text-muted-foreground">
            +{rest.length}
          </span>
        )}
      </span>
      <span className="block truncate text-[12px] text-muted-foreground">
        {first.email}
      </span>
    </>
  )
  return org.deactivated ? (
    <div className="min-w-0">{body}</div>
  ) : (
    <button
      type="button"
      onClick={onOpen}
      title="View or edit the administrator"
      className="block min-w-0 max-w-full rounded-btn text-left hover:text-primary"
    >
      {body}
    </button>
  )
}

/** Administrators of an organisation, shown in its edit panel with their details editable. */
function AdminsSection({ org }: { org: OrganisationDto }) {
  const queryClient = useQueryClient()
  const admins = useQuery({
    queryKey: [...ADMINS_KEY, org.tenantId],
    queryFn: () => fetchTenantAdmins(org.tenantId),
    retry: false,
  })
  const [editingId, setEditingId] = useState<string | null>(null)

  return (
    <div className="border-t border-border pt-4">
      <p className="mb-2 text-[12px] font-semibold tracking-[0.04em] text-muted-foreground uppercase">
        Administrator
      </p>
      {admins.isPending && (
        <p className="text-[12.5px] text-muted-foreground">Loading…</p>
      )}
      {admins.isError && (
        <p className="text-[12.5px] text-[var(--rust)]">
          {friendlyMessage(
            admins.error,
            "Could not load the organisation's administrators."
          )}
        </p>
      )}
      {admins.data?.length === 0 && (
        <p className="text-[12.5px] text-muted-foreground">
          This organisation has no administrator.
        </p>
      )}
      <div className="flex flex-col gap-3">
        {admins.data?.map((admin) =>
          editingId === admin.userId ? (
            <AdminEditor
              key={admin.userId}
              org={org}
              admin={admin}
              others={(admins.data ?? []).filter(
                (other) => other.userId !== admin.userId
              )}
              onCancel={() => setEditingId(null)}
              onSaved={() => {
                setEditingId(null)
                void queryClient.invalidateQueries({ queryKey: ADMINS_KEY })
              }}
            />
          ) : (
            <AdminCard
              key={admin.userId}
              org={org}
              admin={admin}
              onEdit={() => setEditingId(admin.userId)}
            />
          )
        )}
      </div>
    </div>
  )
}

function AdminCard({
  org,
  admin,
  onEdit,
}: {
  org: OrganisationDto
  admin: TenantAdminDto
  onEdit: () => void
}) {
  const [sending, setSending] = useState(false)

  async function resend() {
    setSending(true)
    try {
      await resendTenantAdminInvite(org.tenantId, admin.userId)
      toast.success(`Invitation sent to ${admin.email}.`)
    } catch (e) {
      toast.error(friendlyMessage(e, "Could not send the invitation."))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-3.5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-[13.5px] font-semibold">
            {admin.displayName}
          </p>
          <p className="truncate text-[12.5px] text-muted-foreground">
            {admin.email}
          </p>
        </div>
        <Badge
          variant={admin.verified ? "default" : "gold"}
          className="shrink-0"
        >
          {admin.verified ? "Active" : "Invited"}
        </Badge>
      </div>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12.5px]">
        <dt className="text-muted-foreground">Username</dt>
        <dd className="font-mono">{admin.username}</dd>
        <dt className="text-muted-foreground">Last signed in</dt>
        <dd>
          {admin.lastLoginAt ? (
            <FriendlyDate iso={admin.lastLoginAt} />
          ) : (
            <span className="text-muted-foreground">Not yet</span>
          )}
        </dd>
        <dt className="text-muted-foreground">Added</dt>
        <dd>
          <FriendlyDate iso={admin.createdAt} />
        </dd>
      </dl>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        {!admin.verified && (
          <Button
            size="sm"
            variant="outline"
            disabled={sending}
            onClick={() => void resend()}
          >
            {sending && <Loader2 className="animate-spin" />}
            <Mail /> Resend invitation
          </Button>
        )}
        <Button size="sm" variant="outline" onClick={onEdit}>
          <Pencil /> Edit
        </Button>
      </div>
    </div>
  )
}

function AdminEditor({
  org,
  admin,
  others,
  onCancel,
  onSaved,
}: {
  org: OrganisationDto
  admin: TenantAdminDto
  /** The organisation's other administrators - to say at once when an email or username is already theirs. */
  others: TenantAdminDto[]
  onCancel: () => void
  onSaved: () => void
}) {
  const [firstName, setFirstName] = useState(admin.firstName)
  const [lastName, setLastName] = useState(admin.lastName ?? "")
  const [email, setEmail] = useState(admin.email)
  const [username, setUsername] = useState(admin.username)
  const [saving, setSaving] = useState(false)
  const confirm = usePasswordConfirmation()
  const [error, setError] = useState<string | null>(null)

  const emailOk = EMAIL_PATTERN.test(email.trim())
  const wantedUsername = username.trim()
  // Usernames are kept in lower case, so a change of letter case alone is no change.
  const usernameChanged =
    wantedUsername.toLowerCase() !== admin.username.toLowerCase()
  const usernameOk =
    wantedUsername.length >= 3 && USERNAME_PATTERN.test(wantedUsername)
  // Instant feedback against the people on this screen; the server checks everyone else (another organisation's
  // account, or an account whose email this username is) when saving.
  const emailTaken = others.some(
    (other) => other.email.toLowerCase() === email.trim().toLowerCase()
  )
  const usernameTaken =
    usernameChanged &&
    others.some(
      (other) =>
        other.username.toLowerCase() === wantedUsername.toLowerCase() ||
        other.email.toLowerCase() === wantedUsername.toLowerCase()
    )
  const changed =
    firstName.trim() !== admin.firstName ||
    lastName.trim() !== (admin.lastName ?? "") ||
    email.trim() !== admin.email ||
    usernameChanged
  const complete =
    firstName.trim() !== "" &&
    emailOk &&
    usernameOk &&
    !emailTaken &&
    !usernameTaken

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await updateTenantAdmin(confirm.value, org.tenantId, admin.userId, {
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim(),
        username: wantedUsername,
      })
      toast.success("Administrator updated.")
      onSaved()
    } catch (e) {
      confirm.noteError(e)
      setError(friendlyMessage(e, "Could not update the administrator."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      role="group"
      aria-label="Edit administrator"
      className="flex flex-col gap-3 rounded-lg border border-primary bg-card p-3.5"
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="First name" value={firstName} onChange={setFirstName} />
        <Field
          label="Last name"
          optional
          value={lastName}
          onChange={setLastName}
        />
      </div>
      <Field
        label="Email"
        type="email"
        value={email}
        onChange={setEmail}
        error={
          emailTaken
            ? "Another administrator here already has this email."
            : email.trim() !== "" && !emailOk
              ? "Enter a valid email."
              : null
        }
        hint={
          admin.verified
            ? "Where password resets are sent; they can sign in with it too."
            : "They haven't accepted the invitation yet — use Resend invitation after changing it."
        }
      />
      <Field
        label="Username"
        value={username}
        onChange={setUsername}
        error={
          usernameTaken
            ? "Another administrator here already has this username or email."
            : username !== "" && !USERNAME_PATTERN.test(wantedUsername)
              ? "Letters, numbers, dots, hyphens and underscores only"
              : wantedUsername !== "" && wantedUsername.length < 3
                ? "At least 3 characters."
                : null
        }
        hint="What they sign in with - it can't be one another account has. A new username applies from their next sign-in."
      />
      <ConfirmPasswordFields
        confirm={confirm}
        hint="Enter your own password to save this administrator's details."
      />

      {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button
          onClick={() => void save()}
          disabled={!changed || !complete || !confirm.ready || saving}
        >
          {saving && <Loader2 className="animate-spin" />}
          {saving ? "Saving…" : "Save administrator"}
        </Button>
      </div>
    </div>
  )
}

/** An administrator beyond the first, as typed into the Add organisation form. */
interface ExtraAdmin {
  email: string
  username: string
  firstName: string
  lastName: string
}

const EMPTY_EXTRA: ExtraAdmin = { email: "", username: "", firstName: "", lastName: "" }
const MAX_EXTRA_ADMINS = 9
const USERNAME_PATTERN = /^[a-zA-Z0-9._-]+$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function extraComplete(extra: ExtraAdmin): boolean {
  return (
    EMAIL_PATTERN.test(extra.email.trim()) &&
    extra.username.trim().length >= 3 &&
    USERNAME_PATTERN.test(extra.username.trim()) &&
    extra.firstName.trim() !== ""
  )
}

/** Two administrators of one organisation can't share a username or an email. */
function hasDuplicateAdmins(
  first: { adminEmail: string; adminUsername: string },
  extras: ExtraAdmin[]
): boolean {
  const usernames = [first.adminUsername, ...extras.map((e) => e.username)]
    .map((v) => v.trim().toLowerCase())
    .filter(Boolean)
  const emails = [first.adminEmail, ...extras.map((e) => e.email)]
    .map((v) => v.trim().toLowerCase())
    .filter(Boolean)
  return (
    new Set(usernames).size !== usernames.length ||
    new Set(emails).size !== emails.length
  )
}

/** One more administrator's fields, with a way to remove it. */
function ExtraAdminFields({
  number,
  value,
  form,
  others,
  onChange,
  onRemove,
}: {
  number: number
  value: ExtraAdmin
  form: { adminEmail: string; adminUsername: string }
  others: ExtraAdmin[]
  onChange: (field: keyof ExtraAdmin) => (value: string) => void
  onRemove: () => void
}) {
  const taken = (field: "email" | "username") => {
    const mine = value[field].trim().toLowerCase()
    if (mine === "") return false
    const used = [
      field === "email" ? form.adminEmail : form.adminUsername,
      ...others.map((o) => o[field]),
    ].map((v) => v.trim().toLowerCase())
    return used.includes(mine)
  }
  return (
    <div className="mt-1 border-t border-border pt-4">
      <div className="mb-3 flex items-center justify-between">
        <p className="text-[12.5px] font-semibold text-foreground">
          Administrator {number}
        </p>
        <Button
          type="button"
          size="xs"
          variant="ghost"
          className="text-[var(--rust)]"
          onClick={onRemove}
        >
          <Trash2 /> Remove
        </Button>
      </div>
      <div className="flex flex-col gap-4">
        <Field
          label="Email"
          type="email"
          value={value.email}
          onChange={onChange("email")}
          placeholder="second.admin@acme.com"
          error={
            taken("email")
              ? "Another administrator here already has this email."
              : value.email.trim() !== "" && !EMAIL_PATTERN.test(value.email.trim())
                ? "Enter a valid email."
                : null
          }
        />
        <Field
          label="Username"
          value={value.username}
          onChange={onChange("username")}
          error={
            taken("username")
              ? "Another administrator here already has this username."
              : value.username !== "" && !USERNAME_PATTERN.test(value.username)
                ? "Letters, numbers, dots, hyphens and underscores only"
                : null
          }
        />
        <div className="grid grid-cols-2 gap-3">
          <Field
            label="First name"
            value={value.firstName}
            onChange={onChange("firstName")}
          />
          <Field
            label="Last name"
            optional
            value={value.lastName}
            onChange={onChange("lastName")}
          />
        </div>
      </div>
    </div>
  )
}

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
  // More administrators for the same organisation (the backend allows up to 9 beyond the first).
  const [extras, setExtras] = useState<ExtraAdmin[]>([])
  const [saving, setSaving] = useState(false)
  const confirm = usePasswordConfirmation()
  const [error, setError] = useState<string | null>(null)

  const set = (field: keyof typeof form) => (value: string) =>
    setForm((current) => ({ ...current, [field]: value }))
  const setExtra = (index: number, field: keyof ExtraAdmin) => (value: string) =>
    setExtras((current) =>
      current.map((extra, i) => (i === index ? { ...extra, [field]: value } : extra))
    )

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
    form.adminUsername.length >= 3 &&
    extras.every(extraComplete) &&
    !hasDuplicateAdmins(form, extras)

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const result = await provisionOrganisation(confirm.value, {
        ...form,
        adminLastName: form.adminLastName.trim() || undefined,
        additionalAdmins: extras.map((extra) => ({
          email: extra.email.trim(),
          username: extra.username.trim(),
          firstName: extra.firstName.trim(),
          lastName: extra.lastName.trim() || undefined,
        })),
      })
      const count = 1 + (result.additionalAdmins?.length ?? 0)
      toast.success(
        count === 1
          ? `${result.tenant.tenantName} created. ${result.admin.email} can set their password from the email just sent.`
          : `${result.tenant.tenantName} created. ${count} administrators were emailed a link to set their password.`
      )
      onDone()
      onOpenChange(false)
    } catch (e) {
      confirm.noteError(e)
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
            Creates the organisation and its administrators together. Each is
            emailed a link to set their own password — no password is set
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
              {extras.length > 0 ? "Administrator 1" : "First administrator"}
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

          {extras.map((extra, index) => (
            <ExtraAdminFields
              key={index}
              number={index + 2}
              value={extra}
              form={form}
              others={extras.filter((_, i) => i !== index)}
              onChange={(field) => setExtra(index, field)}
              onRemove={() =>
                setExtras((current) => current.filter((_, i) => i !== index))
              }
            />
          ))}
          {extras.length < MAX_EXTRA_ADMINS && (
            <Button
              type="button"
              variant="outline"
              className="self-start"
              onClick={() => setExtras((current) => [...current, { ...EMPTY_EXTRA }])}
            >
              <Plus size={14} aria-hidden="true" /> Add another administrator
            </Button>
          )}

          <ConfirmPasswordFields confirm={confirm} />


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
              disabled={!complete || !confirm.ready || saving}
            >
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
  const confirm = usePasswordConfirmation()
  const [error, setError] = useState<string | null>(null)

  const changed =
    tenantName.trim() !== org.tenantName ||
    subscriptionTier.trim() !== org.subscriptionTier
  const complete = tenantName.trim() !== "" && subscriptionTier.trim() !== ""

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await updateOrganisation(confirm.value, org, {
        tenantName: tenantName.trim(),
        subscriptionTier: subscriptionTier.trim(),
      })
      toast.success(`${tenantName.trim()} updated.`)
      onDone()
      onOpenChange(false)
    } catch (e) {
      confirm.noteError(e)
      setError(friendlyMessage(e, "Could not update the organisation."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-[520px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>Edit {org.tenantName}</SheetTitle>
          <SheetDescription>
            Change the name and plan, and see or correct the administrator.
            Suspending, deactivating and destroying keys stay on the list.
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
          <ConfirmPasswordFields confirm={confirm} />

          {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
          <AdminsSection org={org} />
        </div>

        <SheetFooter className="border-t border-border">
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={saving}
            >
              Close
            </Button>
            <Button
              onClick={() => void save()}
              disabled={!changed || !complete || !confirm.ready || saving}
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
  // The label is tied to its input, so a screen reader announces it and clicking the label focuses the field.
  const id = useId()
  return (
    <div>
      <label
        htmlFor={id}
        className="mb-1 block text-[12px] font-medium text-foreground"
      >
        {label}
        {optional && (
          <span className="ml-1 font-normal text-muted-foreground">
            (optional)
          </span>
        )}
      </label>
      <Input
        id={id}
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
  "editing-on": (org) => ({
    title: `Allow online editing for ${org.tenantName}?`,
    description:
      "Its administrator can then turn it on or off for their people, and sees the Online Editing page. Nothing changes for its users until they do.",
    confirm: "Allow",
    done: `Online editing is allowed for ${org.tenantName}.`,
  }),
  "editing-off": (org) => ({
    title: `Stop online editing for ${org.tenantName}?`,
    description:
      "Nobody there gets the editor, \"Edit online\" disappears, an editor already open can no longer save, and its administrator no longer sees the Online Editing page. Their own setting is kept in case you allow it again.",
    confirm: "Stop online editing",
    done: `Online editing is stopped for ${org.tenantName}.`,
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
  const confirm = usePasswordConfirmation()
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
        confirm.value,
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
        confirm.noteError(e)
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
          <ConfirmPasswordFields confirm={confirm} />

          {error && <p className="text-[12.5px] text-[var(--rust)]">{error}</p>}
        </div>
        <SheetFooter className="border-t border-border">
          <Button
            className="bg-[var(--rust)] text-white hover:bg-[var(--rust)]/90"
            disabled={!ready || !confirm.ready || busy}
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
