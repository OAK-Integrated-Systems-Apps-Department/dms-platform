"use client"

import { useState, type ReactNode } from "react"
import {
  AlertTriangle,
  Check,
  Copy,
  KeyRound,
  Link2,
  Loader2,
  ShieldCheck,
  Trash2,
  UserPlus,
  Users,
} from "lucide-react"
import { toast } from "sonner"

import { AccessSheet, type AccessTarget } from "@/components/folders/access-sheet"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { DateTimePicker } from "@/components/ui/datetime-picker"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  defaultExpiryValue,
  eventLabel,
  linkStatusLabel,
  toIsoInstant,
  type GuestGrant,
  type LinkCreated,
  type ShareAccess,
  type ShareLink,
  type ShareTargetType,
} from "@/lib/sharing/api"
import {
  useCreateGrant,
  useCreateLink,
  useDeleteGrant,
  useEligibility,
  useGuestGrants,
  useLinks,
  useRevokeLink,
  useSetConfidential,
  useShareEvents,
  useSharingSettings,
  useUpdateGrant,
} from "@/lib/sharing/use-sharing"

/** What is being shared: a document or a folder (a folder shares everything inside it). */
export type ShareTarget = {
  kind: ShareTargetType
  id: string
  name: string
}

const ACCESS_OPTIONS: { value: ShareAccess; label: string }[] = [
  { value: "view", label: "View only" },
  { value: "download", label: "View & download" },
]

function formatWhen(iso: string | null): string {
  if (!iso) return "Never"
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  })
}

/**
 * Google-Drive-style Share sheet for a document or a folder: internal people and roles (the existing access sheet),
 * external guests, share links, the confidential flag and the activity trail (1.5, SEA-FR-001..007).
 */
export function ShareSheet({
  target,
  open,
  onOpenChange,
}: {
  target: ShareTarget
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [tab, setTab] = useState<"share" | "activity">("share")
  const [accessOpen, setAccessOpen] = useState(false)
  const { hasPermission } = usePermissions()
  const { isTenantAdmin } = useIsTenantAdmin()
  const can = (code: string) => isTenantAdmin || hasPermission(code)

  const settings = useSharingSettings()
  const externalOn = settings.data?.externalSharingEnabled ?? false
  const eligibility = useEligibility(target.kind, target.id, open && externalOn)

  const canLink = can(PermissionCodes.SEA_CREATE_LINK)
  const canGuests = can(PermissionCodes.SEA_INVITE_GUEST)
  const canSeeGuests = can(PermissionCodes.SEA_VIEW_GUESTS) || canGuests
  const canActivity = can(PermissionCodes.SEA_VIEW_ACTIVITY)
  const blocked = eligibility.data ? !eligibility.data.canShare : false
  const [override, setOverride] = useState(false)
  const [overrideReason, setOverrideReason] = useState("")
  const mayOverride = !!eligibility.data?.canOverride
  const overrideActive = blocked && mayOverride && override
  const externalDisabled = blocked && !overrideActive
  const accessTarget: AccessTarget = target

  return (
    <>
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent
          side="right"
          className="w-full gap-0 overflow-y-auto data-[side=right]:sm:max-w-[560px]"
        >
          <SheetHeader className="border-b border-border">
            <SheetTitle className="flex items-center gap-2">
              <Users size={18} className="text-primary" />
              Share &ldquo;{target.name}&rdquo;
            </SheetTitle>
            <SheetDescription>
              {target.kind === "folder"
                ? "Everything inside this folder is shared with it."
                : "Choose who can open this document, inside and outside your organisation."}
            </SheetDescription>
            {canActivity && (
              <div className="pt-2">
                <SegmentedControl
                  aria-label="Share sections"
                  value={tab}
                  onChange={setTab}
                  options={[
                    { value: "share", label: "Share" },
                    { value: "activity", label: "Activity" },
                  ]}
                />
              </div>
            )}
          </SheetHeader>

          {tab === "activity" ? (
            <ActivityList target={target} />
          ) : (
            <div className="flex flex-col gap-6 p-5">
              <Section
                icon={<ShieldCheck size={15} />}
                title="People with access"
                description="Users and roles in your organisation, at view, edit or manage level."
              >
                <Button variant="outline" onClick={() => setAccessOpen(true)}>
                  <ShieldCheck />
                  Manage internal access
                </Button>
              </Section>

              {settings.isPending ? (
                <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
                  <Loader2 size={14} className="animate-spin" /> Loading…
                </p>
              ) : !externalOn ? (
                <p className="rounded-btn border border-border bg-muted/50 p-3 text-[13px] text-muted-foreground">
                  Sharing outside your organisation is switched off.{" "}
                  {isTenantAdmin ? (
                    <a
                      href="/admin/sharing"
                      className="font-semibold text-primary underline-offset-2 hover:underline"
                    >
                      Turn it on in Admin &rarr; External sharing
                    </a>
                  ) : (
                    "Ask an administrator to turn it on."
                  )}
                </p>
              ) : (
                <>
                  {blocked && (
                    <div
                      role="alert"
                      className="flex flex-col gap-2 rounded-btn border border-[var(--gold)]/40 bg-[var(--gold-tint)] p-3 text-[13px] text-[var(--gold-foreground)]"
                    >
                      <p className="flex items-center gap-2 font-semibold">
                        <AlertTriangle size={15} />
                        This can&apos;t be shared outside your organisation
                      </p>
                      <ul className="list-disc pl-5">
                        {eligibility.data?.reasons.map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                      {mayOverride && (
                        <div className="flex flex-col gap-2 pt-1">
                          <Checkbox
                            label="Override as administrator"
                            hint="Recorded in the audit trail with your reason."
                            checked={override}
                            onCheckedChange={setOverride}
                          />
                          {override && (
                            <Input
                              aria-label="Reason for the override"
                              placeholder="Why is this being shared?"
                              value={overrideReason}
                              onChange={(e) =>
                                setOverrideReason(e.target.value)
                              }
                            />
                          )}
                        </div>
                      )}
                    </div>
                  )}

                  {canSeeGuests && (
                    <GuestSection
                      target={target}
                      canInvite={canGuests}
                      canUpdate={can(PermissionCodes.SEA_UPDATE_GUEST)}
                      canRevoke={can(PermissionCodes.SEA_REVOKE_GUEST)}
                      disabled={externalDisabled}
                      overrideActive={overrideActive}
                      overrideReason={overrideReason}
                    />
                  )}

                  {canLink && (
                    <LinkSection
                      target={target}
                      canRestrict={can(PermissionCodes.SEA_SET_EXPIRY_PASSWORD)}
                      canChooseLevel={can(
                        PermissionCodes.SEA_SET_PERMISSION_LEVEL
                      )}
                      canRevoke={can(PermissionCodes.SEA_REVOKE_SINGLE)}
                      disabled={externalDisabled}
                      overrideActive={overrideActive}
                      overrideReason={overrideReason}
                      defaultExpiryDays={settings.data?.defaultExpiryDays ?? 7}
                      maxExpiryDays={settings.data?.maxExpiryDays ?? 90}
                    />
                  )}

                  <ConfidentialToggle target={target} />
                </>
              )}
            </div>
          )}
        </SheetContent>
      </Sheet>
      {accessOpen && (
        <AccessSheet
          target={accessTarget}
          open
          onOpenChange={(next) => !next && setAccessOpen(false)}
        />
      )}
    </>
  )
}

function Section({
  icon,
  title,
  description,
  children,
}: {
  icon: ReactNode
  title: string
  description?: string
  children: ReactNode
}) {
  return (
    <section className="flex flex-col gap-3">
      <div>
        <h3 className="flex items-center gap-2 text-[14px] font-semibold">
          <span className="text-primary">{icon}</span>
          {title}
        </h3>
        {description && (
          <p className="mt-0.5 text-[12.5px] text-muted-foreground">
            {description}
          </p>
        )}
      </div>
      {children}
    </section>
  )
}

// --- guests -----------------------------------------------------------------------------------

function GuestSection({
  target,
  canInvite,
  canUpdate,
  canRevoke,
  disabled,
  overrideActive,
  overrideReason,
}: {
  target: ShareTarget
  canInvite: boolean
  canUpdate: boolean
  canRevoke: boolean
  disabled: boolean
  overrideActive: boolean
  overrideReason: string
}) {
  const grants = useGuestGrants(target.kind, target.id)
  const create = useCreateGrant()
  const [email, setEmail] = useState("")
  const [access, setAccess] = useState<ShareAccess>("view")
  const [expiry, setExpiry] = useState("")

  function add() {
    create.mutate(
      {
        email: email.trim(),
        targetType: target.kind,
        targetId: target.id,
        access,
        expiresAt: toIsoInstant(expiry),
        overrideRestriction: overrideActive || undefined,
        overrideReason: overrideActive ? overrideReason : undefined,
      },
      {
        onSuccess: () => {
          toast.success("Invitation sent. They'll find it in the client portal.")
          setEmail("")
          setExpiry("")
        },
        onError: (error) => toast.error(friendlyMessage(error)),
      }
    )
  }

  const valid = /^\S+@\S+\.\S+$/.test(email.trim())

  return (
    <Section
      icon={<UserPlus size={15} />}
      title="Guests"
      description="External people who sign in to a client portal. They only ever see what you share here."
    >
      {canInvite && (
        <div className="flex flex-col gap-2.5 rounded-btn border border-border p-3">
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              type="email"
              aria-label="Guest email"
              placeholder="Add a guest by email"
              value={email}
              disabled={disabled}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <SegmentedControl
              aria-label="Guest access"
              value={access}
              onChange={setAccess}
              options={ACCESS_OPTIONS}
              disabled={disabled}
            />
          </div>
          <div>
            <Label>Access ends (optional)</Label>
            <div className="flex items-center gap-2">
              <DateTimePicker
                value={expiry}
                onChange={setExpiry}
                placeholder="No end date"
              />
              {expiry && (
                <Button variant="ghost" size="xs" onClick={() => setExpiry("")}>
                  Clear
                </Button>
              )}
            </div>
          </div>
          <div>
            <Button
              onClick={add}
              disabled={disabled || !valid || create.isPending}
            >
              {create.isPending ? (
                <Loader2 className="animate-spin" />
              ) : (
                <UserPlus />
              )}
              Invite guest
            </Button>
          </div>
        </div>
      )}
      {grants.isPending ? (
        <p className="text-[13px] text-muted-foreground">Loading guests…</p>
      ) : (grants.data?.length ?? 0) === 0 ? (
        <p className="text-[13px] text-muted-foreground">
          No guests have access yet.
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-btn border border-border">
          {grants.data!.map((grant) => (
            <GrantRow
              key={grant.grantId}
              grant={grant}
              canUpdate={canUpdate}
              canRevoke={canRevoke}
            />
          ))}
        </ul>
      )}
    </Section>
  )
}

function GrantRow({
  grant,
  canUpdate,
  canRevoke,
}: {
  grant: GuestGrant
  canUpdate: boolean
  canRevoke: boolean
}) {
  const update = useUpdateGrant()
  const remove = useDeleteGrant()
  const revoked = !!grant.revokedAt
  return (
    <li className="flex flex-col gap-2 p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-[13.5px] font-medium">
            {grant.displayName || grant.email}
          </p>
          <p className="truncate text-[12px] text-muted-foreground">
            {grant.email} · {grant.guestStatus}
            {grant.expiresAt && ` · until ${formatWhen(grant.expiresAt)}`}
          </p>
        </div>
        {revoked && <Badge variant="rust">Removed</Badge>}
      </div>
      {!revoked && (
        <div className="flex flex-wrap items-center gap-2">
          <select
            aria-label={`Access for ${grant.email}`}
            className="h-8 rounded-btn border border-input bg-transparent px-2 text-[13px]"
            value={grant.access}
            disabled={!canUpdate || update.isPending}
            onChange={(e) =>
              update.mutate(
                { id: grant.grantId, access: e.target.value as ShareAccess },
                { onError: (error) => toast.error(friendlyMessage(error)) }
              )
            }
          >
            <option value="view">Can view</option>
            <option value="download">Can view & download</option>
          </select>
          {canRevoke && (
            <Button
              variant="ghost"
              size="xs"
              disabled={remove.isPending}
              onClick={() =>
                remove.mutate(grant.grantId, {
                  onSuccess: () => toast.success("Guest access removed."),
                  onError: (error) => toast.error(friendlyMessage(error)),
                })
              }
            >
              <Trash2 />
              Remove
            </Button>
          )}
        </div>
      )}
    </li>
  )
}

// --- links ------------------------------------------------------------------------------------

function LinkSection({
  target,
  canRestrict,
  canChooseLevel,
  canRevoke,
  disabled,
  overrideActive,
  overrideReason,
  defaultExpiryDays,
  maxExpiryDays,
}: {
  target: ShareTarget
  canRestrict: boolean
  canChooseLevel: boolean
  canRevoke: boolean
  disabled: boolean
  overrideActive: boolean
  overrideReason: string
  defaultExpiryDays: number
  maxExpiryDays: number
}) {
  const links = useLinks(target.kind, target.id)
  const create = useCreateLink()
  const [access, setAccess] = useState<ShareAccess>("view")
  const [expiry, setExpiry] = useState(() =>
    defaultExpiryValue(defaultExpiryDays)
  )
  const [more, setMore] = useState(false)
  const [maxViews, setMaxViews] = useState("")
  const [password, setPassword] = useState("")
  const [recipient, setRecipient] = useState("")
  const [message, setMessage] = useState("")
  const [created, setCreated] = useState<LinkCreated | null>(null)

  const latest = defaultExpiryValue(maxExpiryDays)
  const tooLong = !!expiry && expiry > latest

  function generate() {
    create.mutate(
      {
        targetType: target.kind,
        targetId: target.id,
        access,
        expiresAt: canRestrict ? toIsoInstant(expiry) : undefined,
        maxViews:
          canRestrict && maxViews ? Math.max(1, Number(maxViews)) : undefined,
        password: canRestrict && password ? password : undefined,
        recipientEmail: recipient.trim() || undefined,
        message: recipient.trim() && message.trim() ? message.trim() : undefined,
        overrideRestriction: overrideActive || undefined,
        overrideReason: overrideActive ? overrideReason : undefined,
      },
      {
        onSuccess: (result) => {
          setCreated(result)
          setPassword("")
          setMaxViews("")
          setRecipient("")
          setMessage("")
          if (result.emailSent) toast.success("Link emailed to the recipient.")
        },
        onError: (error) => toast.error(friendlyMessage(error)),
      }
    )
  }

  return (
    <Section
      icon={<Link2 size={15} />}
      title="Share link"
      description="Anyone with the link can open it - until it expires or you turn it off."
    >
      <div className="flex flex-col gap-3 rounded-btn border border-border p-3">
        {canChooseLevel && (
          <SegmentedControl
            aria-label="Link access"
            value={access}
            onChange={setAccess}
            options={ACCESS_OPTIONS}
            disabled={disabled}
          />
        )}
        {canRestrict && (
          <div>
            <Label>Link expires</Label>
            <DateTimePicker value={expiry} onChange={setExpiry} />
            {tooLong && (
              <p className="mt-1 text-[12px] text-destructive">
                Your organisation caps links at {maxExpiryDays} days.
              </p>
            )}
          </div>
        )}
        {canRestrict && (
          <>
            <button
              type="button"
              className="w-fit text-[12.5px] font-medium text-primary hover:underline"
              onClick={() => setMore((v) => !v)}
            >
              {more ? "Fewer options" : "More options"}
            </button>
            {more && (
              <div className="flex flex-col gap-3">
                <div>
                  <Label>Password (optional)</Label>
                  <Input
                    type="password"
                    autoComplete="new-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={disabled}
                  />
                </div>
                <div>
                  <Label>Limit number of views (optional)</Label>
                  <Input
                    type="number"
                    min={1}
                    inputMode="numeric"
                    value={maxViews}
                    onChange={(e) => setMaxViews(e.target.value)}
                    disabled={disabled}
                  />
                </div>
                <div>
                  <Label>Email the link to (optional)</Label>
                  <Input
                    type="email"
                    value={recipient}
                    onChange={(e) => setRecipient(e.target.value)}
                    disabled={disabled}
                  />
                </div>
                {recipient.trim() && (
                  <div>
                    <Label>Message</Label>
                    <Textarea
                      rows={3}
                      value={message}
                      onChange={(e) => setMessage(e.target.value)}
                    />
                  </div>
                )}
              </div>
            )}
          </>
        )}
        <div>
          <Button
            onClick={generate}
            disabled={disabled || tooLong || create.isPending}
          >
            {create.isPending ? (
              <Loader2 className="animate-spin" />
            ) : (
              <Link2 />
            )}
            Create link
          </Button>
        </div>
        {created && <CopyBox result={created} />}
      </div>

      {links.isPending ? (
        <p className="text-[13px] text-muted-foreground">Loading links…</p>
      ) : (links.data?.length ?? 0) === 0 ? (
        <p className="text-[13px] text-muted-foreground">
          No links yet. The address of a link is only shown once, when you
          create it.
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-btn border border-border">
          {links.data!.map((link) => (
            <LinkRow key={link.linkId} link={link} canRevoke={canRevoke} />
          ))}
        </ul>
      )}
    </Section>
  )
}

function CopyBox({ result }: { result: LinkCreated }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(result.url)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error("Could not copy - select the address and copy it by hand.")
    }
  }
  return (
    <div className="flex flex-col gap-1.5 rounded-btn bg-[var(--primary-tint)] p-2.5">
      <p className="text-[12px] font-semibold text-primary">
        Link created - copy it now, it won&apos;t be shown again.
      </p>
      <div className="flex gap-2">
        <Input readOnly value={result.url} onFocus={(e) => e.target.select()} />
        <Button variant="outline" onClick={copy} aria-label="Copy link">
          {copied ? <Check /> : <Copy />}
        </Button>
      </div>
    </div>
  )
}

function LinkRow({ link, canRevoke }: { link: ShareLink; canRevoke: boolean }) {
  const revoke = useRevokeLink()
  const [confirm, setConfirm] = useState(false)
  const status = linkStatusLabel(link)
  const live = link.status === "active" || link.status === "locked"
  return (
    <li className="flex flex-col gap-1.5 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={status.variant}>{status.label}</Badge>
        <Badge variant="outline">
          {link.access === "download" ? "View & download" : "View only"}
        </Badge>
        {link.passwordProtected && (
          <Badge variant="muted">
            <KeyRound /> Password
          </Badge>
        )}
        {link.overridden && <Badge variant="gold">Admin override</Badge>}
      </div>
      <p className="text-[12px] text-muted-foreground">
        Expires {formatWhen(link.expiresAt)} · opened {link.viewCount}
        {link.maxViews ? ` of ${link.maxViews}` : ""} times
        {link.recipientEmail && ` · sent to ${link.recipientEmail}`} · by{" "}
        {link.createdByName}
      </p>
      {canRevoke && live && (
        <div>
          <Button variant="ghost" size="xs" onClick={() => setConfirm(true)}>
            <Trash2 />
            Revoke
          </Button>
        </div>
      )}
      <ConfirmActionDialog
        open={confirm}
        onOpenChange={setConfirm}
        title="Revoke this link?"
        description="Anyone using it will lose access immediately."
        confirmLabel="Revoke link"
        pendingLabel="Revoking…"
        onConfirm={async () => {
          try {
            await revoke.mutateAsync(link.linkId)
            toast.success("Link revoked.")
            return null
          } catch (error) {
            return friendlyMessage(error)
          }
        }}
      />
    </li>
  )
}

// --- confidential + activity --------------------------------------------------------------------

function ConfidentialToggle({ target }: { target: ShareTarget }) {
  const setFlag = useSetConfidential()
  const [on, setOn] = useState(false)
  const [touched, setTouched] = useState(false)
  const eligibility = useEligibility(target.kind, target.id)
  const flagged = touched ? on : (eligibility.data?.confidential ?? false)

  function change(next: boolean) {
    setFlag.mutate(
      { type: target.kind, id: target.id, confidential: next },
      {
        onSuccess: (result) => {
          setTouched(true)
          setOn(next)
          if (next && (result.flaggedLinks || result.revokedLinks)) {
            toast.warning(
              result.revokedLinks
                ? `${result.revokedLinks} existing link(s) were revoked.`
                : `${result.flaggedLinks} existing link(s) were flagged for administrator review.`
            )
          } else {
            toast.success(
              next ? "Marked confidential." : "No longer confidential."
            )
          }
        },
        onError: (error) => toast.error(friendlyMessage(error)),
      }
    )
  }

  return (
    <Section
      icon={<ShieldCheck size={15} />}
      title="Confidential"
      description="Confidential items can't be shared outside your organisation unless an administrator overrides it."
    >
      <label className="flex items-center gap-3 text-[13.5px]">
        <Switch
          checked={flagged}
          disabled={setFlag.isPending}
          onCheckedChange={change}
        />
        Mark{" "}
        {target.kind === "folder" ? "this folder and its contents" : "this document"}{" "}
        as confidential
      </label>
    </Section>
  )
}

function ActivityList({ target }: { target: ShareTarget }) {
  const [page, setPage] = useState(0)
  const events = useShareEvents(target.kind, target.id, page)
  return (
    <div className="flex flex-col gap-3 p-5">
      {events.isPending ? (
        <p className="text-[13px] text-muted-foreground">Loading activity…</p>
      ) : (events.data?.items.length ?? 0) === 0 ? (
        <p className="text-[13px] text-muted-foreground">
          No sharing activity yet.
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-btn border border-border">
          {events.data!.items.map((event) => (
            <li key={event.eventId} className="p-3 text-[13px]">
              <p className="font-medium">{eventLabel(event.type)}</p>
              <p className="text-[12px] text-muted-foreground">
                {event.actor} · {formatWhen(event.at)}
                {event.ip && ` · ${event.ip}`}
                {event.detail && ` · ${event.detail}`}
              </p>
            </li>
          ))}
        </ul>
      )}
      {events.data && events.data.total > (page + 1) * events.data.size && (
        <Button variant="outline" onClick={() => setPage((p) => p + 1)}>
          Older activity
        </Button>
      )}
      {page > 0 && (
        <Button variant="ghost" onClick={() => setPage((p) => p - 1)}>
          Newer activity
        </Button>
      )}
    </div>
  )
}
