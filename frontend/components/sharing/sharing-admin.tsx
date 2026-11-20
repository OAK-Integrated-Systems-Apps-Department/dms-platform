"use client"

import { useState } from "react"
import { Loader2, Trash2, UserPlus } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import { friendlyMessage } from "@/lib/api-error"
import {
  linkStatusLabel,
  type Guest,
  type LinkListFilter,
  type ShareLink,
  type SharingSettings,
} from "@/lib/sharing/api"
import {
  useAllLinks,
  useClearReview,
  useDeleteGuest,
  useGuests,
  useInviteGuest,
  useRevokeLink,
  useSaveSharingSettings,
  useSetGuestStatus,
  useSharingSettings,
} from "@/lib/sharing/use-sharing"

type Tab = "policy" | "links" | "guests"

const PAGE_SIZE = 25

function formatWhen(iso: string | null): string {
  if (!iso) return "-"
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  })
}

/** Tenant-admin screen for 1.5: the sharing policy, every external link (with the review queue) and guest accounts. */
export function SharingAdmin() {
  const [tab, setTab] = useState<Tab>("policy")
  return (
    <div className="flex flex-col gap-5">
      <SegmentedControl
        aria-label="Sharing sections"
        value={tab}
        onChange={setTab}
        options={[
          { value: "policy", label: "Policy" },
          { value: "links", label: "External links" },
          { value: "guests", label: "Guest accounts" },
        ]}
      />
      {tab === "policy" && <PolicyPanel />}
      {tab === "links" && <LinksPanel />}
      {tab === "guests" && <GuestsPanel />}
    </div>
  )
}

// --- policy ------------------------------------------------------------------------------------

function PolicyPanel() {
  const settings = useSharingSettings()
  if (settings.isPending) return <LoadingState message="Loading the sharing policy…" />
  if (settings.isError || !settings.data)
    return (
      <LoadFailed
        error={settings.error}
        fallback="Could not load the sharing policy."
        onRetry={() => settings.refetch()}
      />
    )
  return <PolicyForm key={settings.dataUpdatedAt} initial={settings.data} />
}

function NumberField({
  label,
  hint,
  value,
  min,
  onChange,
}: {
  label: string
  hint: string
  value: number
  min: number
  onChange: (n: number) => void
}) {
  return (
    <div>
      <Label>{label}</Label>
      <Input
        type="number"
        min={min}
        inputMode="numeric"
        value={Number.isNaN(value) ? "" : value}
        onChange={(e) => onChange(e.target.value === "" ? NaN : Number(e.target.value))}
        className="max-w-[160px]"
      />
      <p className="mt-1 text-[12px] text-muted-foreground">{hint}</p>
    </div>
  )
}

function PolicyForm({ initial }: { initial: SharingSettings }) {
  const save = useSaveSharingSettings()
  const [draft, setDraft] = useState<SharingSettings>(initial)
  const set = <K extends keyof SharingSettings>(k: K, v: SharingSettings[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))

  const numbers = [
    draft.defaultExpiryDays,
    draft.maxExpiryDays,
    draft.maxPasswordAttempts,
    draft.lockoutMinutes,
    draft.guestInactivityDays,
  ]
  const invalid =
    numbers.some((n) => Number.isNaN(n) || n < 1) ||
    draft.defaultExpiryDays > draft.maxExpiryDays

  return (
    <Panel title="External sharing policy">
      <div className="flex flex-col gap-5 pt-3">
        <label className="flex items-center gap-3 text-[13.5px]">
          <Switch
            checked={draft.externalSharingEnabled}
            onCheckedChange={(v) => set("externalSharingEnabled", v)}
          />
          Allow people to share outside the organisation
        </label>
        <div className="grid gap-5 sm:grid-cols-2">
          <NumberField
            label="Default link expiry (days)"
            hint="Pre-filled when someone creates a link."
            min={1}
            value={draft.defaultExpiryDays}
            onChange={(n) => set("defaultExpiryDays", n)}
          />
          <NumberField
            label="Longest allowed expiry (days)"
            hint="Links can never last longer than this."
            min={1}
            value={draft.maxExpiryDays}
            onChange={(n) => set("maxExpiryDays", n)}
          />
          <NumberField
            label="Wrong passwords before a link locks"
            hint="Applies to password-protected links."
            min={1}
            value={draft.maxPasswordAttempts}
            onChange={(n) => set("maxPasswordAttempts", n)}
          />
          <NumberField
            label="Lockout (minutes)"
            hint="How long a locked link stays blocked."
            min={1}
            value={draft.lockoutMinutes}
            onChange={(n) => set("lockoutMinutes", n)}
          />
          <NumberField
            label="Disable unused guests after (days)"
            hint="Guests who haven't signed in for this long are disabled."
            min={1}
            value={draft.guestInactivityDays}
            onChange={(n) => set("guestInactivityDays", n)}
          />
        </div>
        <div>
          <Label>When something is marked confidential</Label>
          <SegmentedControl
            aria-label="Reclassification policy"
            value={draft.onReclassify}
            onChange={(v) => set("onReclassify", v)}
            options={[
              { value: "flag", label: "Flag links for review" },
              { value: "revoke", label: "Revoke links automatically" },
            ]}
          />
        </div>
        {draft.defaultExpiryDays > draft.maxExpiryDays && (
          <p role="alert" className="text-[12.5px] text-destructive">
            The default expiry can&apos;t be longer than the longest allowed.
          </p>
        )}
        <div>
          <Button
            disabled={invalid || save.isPending}
            onClick={() =>
              save.mutate(draft, {
                onSuccess: () => toast.success("Sharing policy saved."),
                onError: (e) => toast.error(friendlyMessage(e)),
              })
            }
          >
            {save.isPending && <Loader2 className="animate-spin" />}
            Save policy
          </Button>
        </div>
      </div>
    </Panel>
  )
}

// --- links -------------------------------------------------------------------------------------

type LinkFilter = LinkListFilter | "all"

function LinksPanel() {
  const [filter, setFilter] = useState<LinkFilter>("review")
  const [page, setPage] = useState(0)
  const links = useAllLinks(filter === "all" ? undefined : filter, page, PAGE_SIZE)
  return (
    <Panel title="External links">
      <div className="flex flex-col gap-4 pt-3">
        <SegmentedControl
          aria-label="Link filter"
          value={filter}
          onChange={(v) => {
            setFilter(v)
            setPage(0)
          }}
          options={[
            { value: "review", label: "Needs review" },
            { value: "active", label: "Active" },
            { value: "expired", label: "Expired" },
            { value: "revoked", label: "Revoked" },
            { value: "all", label: "All" },
          ]}
        />
        {links.isPending ? (
          <LoadingState message="Loading links…" />
        ) : links.isError ? (
          <LoadFailed
            error={links.error}
            fallback="Could not load the external links."
            onRetry={() => links.refetch()}
          />
        ) : links.data.items.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">
            {filter === "review"
              ? "Nothing is waiting for review."
              : "No links match."}
          </p>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-[13px]">
                <thead className="text-[11.5px] tracking-wide text-muted-foreground uppercase">
                  <tr>
                    <th className="py-2 pr-3">Shared item</th>
                    <th className="py-2 pr-3">Status</th>
                    <th className="py-2 pr-3">Access</th>
                    <th className="py-2 pr-3">Expires</th>
                    <th className="py-2 pr-3">Views</th>
                    <th className="py-2 pr-3">Shared by</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {links.data.items.map((link) => (
                    <LinkTableRow key={link.linkId} link={link} />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between text-[12.5px] text-muted-foreground">
              <span>
                {links.data.total} link{links.data.total === 1 ? "" : "s"}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="xs"
                  disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  disabled={(page + 1) * links.data.size >= links.data.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </Panel>
  )
}

function LinkTableRow({ link }: { link: ShareLink }) {
  const revoke = useRevokeLink()
  const keep = useClearReview()
  const [confirm, setConfirm] = useState(false)
  const status = linkStatusLabel(link)
  const live = link.status === "active" || link.status === "locked"
  return (
    <tr>
      <td className="py-2.5 pr-3">
        <p className="font-medium">{link.targetName}</p>
        <p className="text-[12px] text-muted-foreground">
          {link.targetType === "folder" ? "Folder" : "Document"}
          {link.passwordProtected && " · password"}
          {link.overridden && " · admin override"}
        </p>
      </td>
      <td className="py-2.5 pr-3">
        <Badge variant={status.variant}>{status.label}</Badge>
      </td>
      <td className="py-2.5 pr-3">
        {link.access === "download" ? "View & download" : "View only"}
      </td>
      <td className="py-2.5 pr-3">{formatWhen(link.expiresAt)}</td>
      <td className="py-2.5 pr-3">
        {link.viewCount}
        {link.maxViews ? ` / ${link.maxViews}` : ""}
      </td>
      <td className="py-2.5 pr-3">{link.createdByName}</td>
      <td className="py-2.5 text-right whitespace-nowrap">
        {link.needsReview && live && (
          <Button
            variant="ghost"
            size="xs"
            disabled={keep.isPending}
            onClick={() =>
              keep.mutate(link.linkId, {
                onSuccess: () => toast.success("Link kept."),
                onError: (e) => toast.error(friendlyMessage(e)),
              })
            }
          >
            Keep
          </Button>
        )}
        {live && (
          <Button variant="ghost" size="xs" onClick={() => setConfirm(true)}>
            <Trash2 />
            Revoke
          </Button>
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
            } catch (e) {
              return friendlyMessage(e)
            }
          }}
        />
      </td>
    </tr>
  )
}

// --- guests ------------------------------------------------------------------------------------

function GuestsPanel() {
  const guests = useGuests()
  const invite = useInviteGuest()
  const [email, setEmail] = useState("")
  const valid = /^\S+@\S+\.\S+$/.test(email.trim())
  return (
    <Panel title="Guest accounts">
      <div className="flex flex-col gap-4 pt-3">
        <div className="flex max-w-[520px] gap-2">
          <Input
            type="email"
            aria-label="Guest email"
            placeholder="Invite a guest by email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Button
            disabled={!valid || invite.isPending}
            onClick={() =>
              invite.mutate(
                { email: email.trim() },
                {
                  onSuccess: () => {
                    toast.success("Invitation sent.")
                    setEmail("")
                  },
                  onError: (e) => toast.error(friendlyMessage(e)),
                }
              )
            }
          >
            <UserPlus />
            Invite
          </Button>
        </div>
        {guests.isPending ? (
          <LoadingState message="Loading guests…" />
        ) : guests.isError ? (
          <LoadFailed
            error={guests.error}
            fallback="Could not load the guest accounts."
            onRetry={() => guests.refetch()}
          />
        ) : guests.data.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">
            No guest accounts yet.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-[13px]">
              <thead className="text-[11.5px] tracking-wide text-muted-foreground uppercase">
                <tr>
                  <th className="py-2 pr-3">Guest</th>
                  <th className="py-2 pr-3">Status</th>
                  <th className="py-2 pr-3">Shared items</th>
                  <th className="py-2 pr-3">Last sign-in</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {guests.data.map((guest) => (
                  <GuestRow key={guest.guestId} guest={guest} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Panel>
  )
}

function GuestRow({ guest }: { guest: Guest }) {
  const setStatus = useSetGuestStatus()
  const remove = useDeleteGuest()
  const [confirm, setConfirm] = useState(false)
  const disabled = guest.status === "disabled"
  return (
    <tr>
      <td className="py-2.5 pr-3">
        <p className="font-medium">{guest.displayName || guest.email}</p>
        <p className="text-[12px] text-muted-foreground">{guest.email}</p>
      </td>
      <td className="py-2.5 pr-3">
        <Badge
          variant={
            guest.status === "active"
              ? "default"
              : guest.status === "invited"
                ? "gold"
                : "muted"
          }
        >
          {guest.status === "invited"
            ? "Invited"
            : guest.status === "active"
              ? "Active"
              : guest.disabledReason === "inactivity"
                ? "Disabled (inactive)"
                : "Disabled"}
        </Badge>
      </td>
      <td className="py-2.5 pr-3">{guest.grantCount}</td>
      <td className="py-2.5 pr-3">{formatWhen(guest.lastLoginAt)}</td>
      <td className="py-2.5 text-right whitespace-nowrap">
        <Button
          variant="ghost"
          size="xs"
          disabled={setStatus.isPending}
          onClick={() =>
            setStatus.mutate(
              { id: guest.guestId, status: disabled ? "active" : "disabled" },
              {
                onSuccess: () =>
                  toast.success(disabled ? "Guest enabled." : "Guest disabled."),
                onError: (e) => toast.error(friendlyMessage(e)),
              }
            )
          }
        >
          {disabled ? "Enable" : "Disable"}
        </Button>
        <Button variant="ghost" size="xs" onClick={() => setConfirm(true)}>
          <Trash2 />
          Delete
        </Button>
        <ConfirmActionDialog
          open={confirm}
          onOpenChange={setConfirm}
          title={`Delete ${guest.email}?`}
          description="Their account and everything shared with them is removed."
          confirmLabel="Delete guest"
          pendingLabel="Deleting…"
          onConfirm={async () => {
            try {
              await remove.mutateAsync(guest.guestId)
              toast.success("Guest deleted.")
              return null
            } catch (e) {
              return friendlyMessage(e)
            }
          }}
        />
      </td>
    </tr>
  )
}
