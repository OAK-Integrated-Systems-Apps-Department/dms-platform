"use client"

import { Suspense, useEffect, useState, type FormEvent } from "react"
import { useSearchParams } from "next/navigation"
import { CheckCircle2, Lock } from "lucide-react"

import {
  AuthHeading,
  AuthShell,
  authButtonClass,
  authFieldClass,
  authInputClass,
  authInputIconClass,
} from "@/components/auth/auth-shell"
import { acceptInvite, fetchInvite } from "@/lib/sharing/api"

const MIN_LENGTH = 10

function AcceptContent() {
  const params = useSearchParams()
  const token = params.get("token") ?? ""
  const org = params.get("org")
  const [invite, setInvite] = useState<{
    email: string
    organisation: string
    valid: boolean
  } | null>(null)
  const [password, setPassword] = useState("")
  const [confirm, setConfirm] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  useEffect(() => {
    if (!token) return
    let cancelled = false
    fetchInvite(token)
      .then((r) => {
        if (!cancelled) setInvite(r)
      })
      .catch(() => {
        if (!cancelled) setInvite({ email: "", organisation: "", valid: false })
      })
    return () => {
      cancelled = true
    }
  }, [token])

  const portalHref = org ? `/portal?org=${encodeURIComponent(org)}` : "/portal"
  const tooShort = password.length > 0 && password.length < MIN_LENGTH
  const mismatch = confirm.length > 0 && confirm !== password

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const result = await acceptInvite(token, password)
      if (result.ok) setDone(true)
      else setError("This invitation can't be used any more.")
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not set the password.")
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <AuthShell>
        <div className="mb-3 flex justify-center lg:justify-start">
          <CheckCircle2 size={32} className="text-primary" />
        </div>
        <AuthHeading
          title="You're all set"
          subtitle="Your password is saved. Sign in to the client portal to see what's been shared with you."
        />
        <a href={portalHref} className={authButtonClass}>
          Go to the portal
        </a>
      </AuthShell>
    )
  }

  if (!token || (invite && !invite.valid)) {
    return (
      <AuthShell>
        <AuthHeading
          title="This invitation isn't valid"
          subtitle="It may have expired or already been used. Ask the person who invited you to send a new one."
        />
        <a
          href={portalHref}
          className="text-[13px] font-semibold text-primary hover:underline"
        >
          Go to sign in
        </a>
      </AuthShell>
    )
  }

  return (
    <AuthShell>
      <AuthHeading
        title="Create your password"
        subtitle={
          invite
            ? `${invite.email} · ${invite.organisation}`
            : "Checking your invitation…"
        }
      />
      <form onSubmit={submit} className="space-y-3 sm:space-y-5">
        <div className={authFieldClass}>
          <Lock size={16} className={authInputIconClass} />
          <input
            type="password"
            aria-label="New password"
            autoComplete="new-password"
            placeholder={`New password (at least ${MIN_LENGTH} characters)`}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className={authInputClass}
          />
        </div>
        <div className={authFieldClass}>
          <Lock size={16} className={authInputIconClass} />
          <input
            type="password"
            aria-label="Confirm password"
            autoComplete="new-password"
            placeholder="Confirm password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className={authInputClass}
          />
        </div>
        {(tooShort || mismatch || error) && (
          <p role="alert" className="text-[13px] text-destructive">
            {error ??
              (tooShort
                ? `Use at least ${MIN_LENGTH} characters.`
                : "The two passwords don't match.")}
          </p>
        )}
        <button
          type="submit"
          disabled={
            busy || !invite || password.length < MIN_LENGTH || password !== confirm
          }
          className={authButtonClass}
        >
          {busy ? "Saving…" : "Save password"}
        </button>
      </form>
    </AuthShell>
  )
}

/** Invite acceptance for a guest account: sets the password from the mailed token (SEA-FR-004). */
export default function AcceptInvitePage() {
  return (
    <Suspense fallback={null}>
      <AcceptContent />
    </Suspense>
  )
}
