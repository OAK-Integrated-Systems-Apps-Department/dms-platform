"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { KeyRound, Loader2, ShieldCheck, UserCog } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/ui/loading-state"
import { Badge } from "@/components/ui/badge"
import { friendlyMessage } from "@/lib/api-error"
import {
  changeMyPassword,
  fetchMyIdentity,
  updateMyIdentity,
  type CurrentIdentityDto,
} from "@/lib/users/api"

const IDENTITY_KEY = ["current-user-identity"] as const

/**
 * My Profile for a platform administrator.
 *
 * A platform admin belongs to no tenant, so they have no row in the DMS's
 * own `users` table and `GET /api/v1/users/me` answers 401
 * TENANT_CONTEXT_MISSING for them - by design (see `isSessionExpired` in
 * lib/api-error.ts, which already treats that code as an expected state).
 * Without this panel the normal profile and security cards both render as
 * errors for the one account that administers the platform.
 *
 * Everything is edited here rather than in Keycloak's own console: the
 * backend writes through to Keycloak, so the admin never has to know their
 * account lives there or find a second URL.
 */
export function PlatformAdminProfile() {
  const identity = useQuery({
    queryKey: IDENTITY_KEY,
    queryFn: fetchMyIdentity,
    retry: false,
  })

  if (identity.isPending)
    return <LoadingState message="Loading your account…" />
  if (identity.isError) {
    return (
      <p className="text-[13px] text-[var(--rust)]">
        {friendlyMessage(
          identity.error,
          "Could not load your account details."
        )}
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-5">
      <IdentityCard me={identity.data} />
      <PasswordCard />
    </div>
  )
}

function IdentityCard({ me }: { me: CurrentIdentityDto }) {
  const queryClient = useQueryClient()
  // Seeded from first/last when the token carries them, otherwise from the
  // combined `name` claim - the realm's tokens often have only that one.
  const seeded = me.firstName ?? me.displayName?.split(" ")[0] ?? ""
  const seededLast =
    me.lastName ??
    (me.displayName?.includes(" ")
      ? me.displayName.slice(me.displayName.indexOf(" ") + 1)
      : "")

  const [firstName, setFirstName] = useState(seeded)
  const [lastName, setLastName] = useState(seededLast)
  const [email, setEmail] = useState(me.email ?? "")
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const changed =
    firstName.trim() !== seeded.trim() ||
    lastName.trim() !== seededLast.trim() ||
    email.trim() !== (me.email ?? "")
  const complete = firstName.trim() !== "" && /.+@.+\..+/.test(email.trim())

  const fullName =
    [firstName, lastName].filter(Boolean).join(" ").trim() ||
    me.displayName ||
    me.username ||
    me.email ||
    "Platform administrator"
  const initials =
    fullName
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .filter((c): c is string => Boolean(c) && /[a-z0-9]/i.test(c))
      .join("")
      .toUpperCase() || "PA"

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await updateMyIdentity({
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim(),
      })
      toast.success("Your details were saved.")
      void queryClient.invalidateQueries({ queryKey: IDENTITY_KEY })
    } catch (e) {
      setError(friendlyMessage(e, "Could not save your account details."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="mb-4 flex items-start gap-4">
        <span className="grid size-12 shrink-0 place-items-center rounded-full bg-primary/10 text-[15px] font-semibold text-primary">
          {initials}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate text-[15px] font-semibold">{fullName}</p>
            {me.platformRoles.map((role) => (
              <Badge key={role} variant="outline" className="gap-1 rounded-md">
                <ShieldCheck size={11} aria-hidden="true" />
                {role.replace(/_/g, " ").toLowerCase()}
              </Badge>
            ))}
          </div>
          <p className="mt-1 text-[12px] text-muted-foreground">
            Username <span className="font-mono">{me.username ?? "—"}</span> ·
            no organisation — a platform administrator works across all of them.
          </p>
        </div>
      </div>

      <div className="border-t border-border pt-4">
        <h2 className="mb-1 flex items-center gap-1.5 text-[14px] font-semibold">
          <UserCog size={14} aria-hidden="true" /> Your details
        </h2>
        <p className="mb-4 text-[12.5px] text-muted-foreground">
          Your name and email. Changes apply the next time you sign in.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Labelled label="First name">
            <Input
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              className="h-9 text-[13px]"
            />
          </Labelled>
          <Labelled label="Last name" optional>
            <Input
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              className="h-9 text-[13px]"
            />
          </Labelled>
          <Labelled label="Email" className="sm:col-span-2">
            <Input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="h-9 text-[13px]"
            />
          </Labelled>
        </div>
        {error && (
          <p className="mt-3 text-[12.5px] text-[var(--rust)]">{error}</p>
        )}
        <div className="mt-4 flex justify-end">
          <Button
            size="sm"
            onClick={() => void save()}
            disabled={!changed || !complete || saving}
          >
            {saving && <Loader2 className="animate-spin" />}
            {saving ? "Saving…" : "Save details"}
          </Button>
        </div>
      </div>
    </div>
  )
}

function PasswordCard() {
  const [current, setCurrent] = useState("")
  const [next, setNext] = useState("")
  const [confirm, setConfirm] = useState("")
  const [totp, setTotp] = useState("")
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mismatch = confirm !== "" && next !== confirm
  const tooShort = next !== "" && next.length < 8
  const complete =
    current !== "" && next.length >= 8 && next === confirm && next !== current

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await changeMyPassword({
        currentPassword: current,
        newPassword: next,
        totp: totp.trim() || undefined,
      })
      toast.success("Your password was changed.")
      setCurrent("")
      setNext("")
      setConfirm("")
      setTotp("")
    } catch (e) {
      setError(friendlyMessage(e, "Could not change your password."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-1 flex items-center gap-1.5 text-[14px] font-semibold">
        <KeyRound size={14} aria-hidden="true" /> Password
      </h2>
      <p className="mb-4 max-w-[560px] text-[12.5px] leading-[1.6] text-muted-foreground">
        Your current password is checked before the new one is set. At least 8
        characters. If you have multi-factor authentication enrolled, add a
        current code as well.
      </p>
      <div className="grid max-w-[520px] gap-4">
        <Labelled label="Current password">
          <Input
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            className="h-9 text-[13px]"
          />
        </Labelled>
        <Labelled label="New password">
          <Input
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            className="h-9 text-[13px]"
          />
          {tooShort && (
            <p className="mt-1 text-[11.5px] text-[var(--rust)]">
              Use at least 8 characters.
            </p>
          )}
        </Labelled>
        <Labelled label="Confirm new password">
          <Input
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className="h-9 text-[13px]"
          />
          {mismatch && (
            <p className="mt-1 text-[11.5px] text-[var(--rust)]">
              The two passwords don&apos;t match.
            </p>
          )}
        </Labelled>
        <Labelled label="One-time code" optional>
          <Input
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="Only if you use MFA"
            value={totp}
            onChange={(e) => setTotp(e.target.value)}
            className="h-9 max-w-[200px] text-[13px]"
          />
        </Labelled>
      </div>
      {error && (
        <p className="mt-3 text-[12.5px] text-[var(--rust)]">{error}</p>
      )}
      <div className="mt-4 flex max-w-[520px] justify-end">
        <Button
          size="sm"
          onClick={() => void save()}
          disabled={!complete || saving}
        >
          {saving && <Loader2 className="animate-spin" />}
          {saving ? "Changing…" : "Change password"}
        </Button>
      </div>
    </div>
  )
}

function Labelled({
  label,
  optional,
  className,
  children,
}: {
  label: string
  optional?: boolean
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={className}>
      <label className="mb-1 block text-[12px] font-medium text-foreground">
        {label}
        {optional && (
          <span className="ml-1 font-normal text-muted-foreground">
            (optional)
          </span>
        )}
      </label>
      {children}
    </div>
  )
}
