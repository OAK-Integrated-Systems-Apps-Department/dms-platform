"use client"

import { useState, type FormEvent } from "react"
import { Lock, Mail } from "lucide-react"

import {
  AuthHeading,
  AuthShell,
  authButtonClass,
  authFieldClass,
  authInputClass,
  authInputIconClass,
} from "@/components/auth/auth-shell"
import { GUEST_TOKEN_KEY, portalLogin } from "@/lib/sharing/api"

/** Guest sign-in for the client portal. Guests are scoped to one organisation, so `?org=` (from the invite mail) picks it. */
export function PortalSignIn({
  tenantId,
  notice,
  onSignedIn,
}: {
  tenantId?: string
  notice?: string | null
  onSignedIn: (token: string) => void
}) {
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [organisations, setOrganisations] = useState<
    { tenantId: string; name: string }[] | null
  >(null)

  async function attempt(org?: string) {
    setBusy(true)
    setError(null)
    try {
      const result = await portalLogin(email.trim(), password, org ?? tenantId)
      if (result.status === "ok" && result.token) {
        try {
          sessionStorage.setItem(GUEST_TOKEN_KEY, result.token)
        } catch {
          // Private mode: the token still works for this page load via onSignedIn.
        }
        onSignedIn(result.token)
      } else if (result.status === "choose_organisation") {
        setOrganisations(result.organisations ?? [])
      } else if (result.status === "disabled") {
        setError(
          "This guest account has been disabled. Ask the person who invited you to restore it."
        )
      } else if (result.status === "locked") {
        setError("Too many attempts. Please wait a while and try again.")
      } else {
        setError("That email and password don't match.")
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not sign in.")
    } finally {
      setBusy(false)
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    void attempt()
  }

  if (organisations) {
    return (
      <AuthShell>
        <AuthHeading
          title="Choose an organisation"
          subtitle="Your email has a guest account with more than one organisation."
        />
        <ul className="space-y-2">
          {organisations.map((org) => (
            <li key={org.tenantId}>
              <button
                type="button"
                disabled={busy}
                onClick={() => void attempt(org.tenantId)}
                className="w-full rounded-btn border border-border px-4 py-3 text-left text-[14px] font-medium hover:bg-muted disabled:opacity-60"
              >
                {org.name}
              </button>
            </li>
          ))}
        </ul>
        {error && (
          <p role="alert" className="mt-3 text-[13px] text-destructive">
            {error}
          </p>
        )}
      </AuthShell>
    )
  }

  return (
    <AuthShell>
      <AuthHeading
        title="Client portal"
        subtitle="Sign in to see what has been shared with you."
      />
      {notice && (
        <p className="mb-4 rounded-btn bg-[var(--primary-tint)] p-3 text-[13px] text-primary">
          {notice}
        </p>
      )}
      <form onSubmit={submit} className="space-y-3 sm:space-y-5">
        <div>
          <label htmlFor="portal-email" className="sr-only">
            Email
          </label>
          <div className={authFieldClass}>
            <Mail size={16} className={authInputIconClass} />
            <input
              id="portal-email"
              type="email"
              autoComplete="username"
              placeholder="Email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={authInputClass}
            />
          </div>
        </div>
        <div>
          <label htmlFor="portal-password" className="sr-only">
            Password
          </label>
          <div className={authFieldClass}>
            <Lock size={16} className={authInputIconClass} />
            <input
              id="portal-password"
              type="password"
              autoComplete="current-password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={authInputClass}
            />
          </div>
        </div>
        {error && (
          <p role="alert" className="text-[13px] text-destructive">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={busy || !email.trim() || !password}
          className={authButtonClass}
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </AuthShell>
  )
}
