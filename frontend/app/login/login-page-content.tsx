// app/login/login-page-content.tsx
"use client"

import { useState, type FormEvent } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useMutation } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { AlertTriangle, Eye, EyeOff, Layers, ShieldCheck } from "lucide-react"

import { OtpInput } from "@/components/auth/otp-input"
import { loginCredentialsSchema, type LoginCredentialsValues } from "@/components/auth/schema"
import { API_BASE_URL } from "@/lib/api-config"

interface LoginResponse {
  ok: boolean
  error?: string
  mfaRequired?: boolean
}

/**
 * Two calls to dms-core, not one — see the component doc comment below
 * for why the MFA check has to happen before the real login attempt
 * rather than being inferred from its result.
 *
 * The /auth/login call is wrapped in its own try/catch (unlike the
 * /mfa-required check above, which is allowed to fail silently and just
 * assume MFA isn't required): if the backend is unreachable here, that's
 * the actual sign-in attempt failing, and the user needs to see that —
 * not have it disappear into a rejected mutation with no onError handler.
 */
async function login(username: string, password: string, totp?: string): Promise<LoginResponse> {
  if (!totp) {
    const mfaCheck = await fetch(
      `${API_BASE_URL}/api/v1/auth/mfa-required?username=${encodeURIComponent(username)}`,
      { credentials: "include" }
    )
      .then((r) => (r.ok ? r.json() : { mfaRequired: false }))
      .catch(() => ({ mfaRequired: false }))
    if (mfaCheck.mfaRequired) {
      return { ok: false, mfaRequired: true }
    }
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    // credentials: "include" so the session cookie(s) your Java backend
    // sets on a successful login come back on every request after this.
    credentials: "include",
    body: JSON.stringify({ username, password, totp }),
  })
  } catch {
    return { ok: false, error: "Could not reach the server. Check that the backend is running and try again." }
  }

  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    return { ok: false, error: body.message ?? "Invalid username or password." }
  }
  return { ok: true }
}

/**
 * Two steps in one page, not two routes: "credentials" then, only for
 * accounts with OTP configured, "otp". They have to share this component
 * (rather than the OTP step being its own page/route, the way
 * dms-otp.html was drafted) because Keycloak's direct-grant token
 * endpoint validates username + password + code together in a single
 * request — there's no server-side session to hand off between two
 * separate page loads, so the credentials can only ever be "remembered"
 * for step 2 by staying in this component's state. Your Java backend's
 * login endpoint is the one that actually knows why (Keycloak direct
 * grant validates username + password + code together, with no way to
 * signal "OTP required" separately from "wrong password") — that's also
 * why login() below checks /mfa-required BEFORE attempting login, instead
 * of reacting to a failed login attempt.
 *
 * Rendered inside a <Suspense> boundary by app/login/page.tsx, since
 * useSearchParams() (used for the dev OTP bypass below) requires one.
 */
export function LoginPageContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  // Dev-only: visiting /login?step=otp lands straight on the OTP screen
  // without needing dms-core's /mfa-required endpoint or Keycloak at all.
  // Stripped out entirely in production builds — see the check below.
  const devOtpBypass = process.env.NODE_ENV !== "production" && searchParams.get("step") === "otp"
  const [step, setStep] = useState<"credentials" | "otp">(devOtpBypass ? "otp" : "credentials")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [rememberMe, setRememberMe] = useState(false)
  const [code, setCode] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  // Kept alongside the validated form state below — the OTP step's retry
  // needs the credentials again, and OtpInput is a controlled component
  // that doesn't fit cleanly into react-hook-form's register() pattern.
  const [savedCredentials, setSavedCredentials] = useState({ username: "", password: "" })

  const {
    register,
    handleSubmit,
    formState: { errors: credentialErrors },
  } = useForm<LoginCredentialsValues>({
    resolver: zodResolver(loginCredentialsSchema),
    defaultValues: { username: "", password: "" },
  })

  const mutation = useMutation({
    mutationFn: ({ username, password, totp }: { username: string; password: string; totp?: string }) =>
      login(username, password, totp),
    onSuccess: (result) => {
      if (result.ok) {
        router.push("/")
        router.refresh()
        return
      }
      if (result.mfaRequired) {
        setStep("otp")
      }
    },
  })

  function handleCredentialsSubmit(values: LoginCredentialsValues) {
    mutation.reset()
    setSavedCredentials(values)
    mutation.mutate({ username: values.username, password: values.password })
  }

  function handleOtpSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate({ ...savedCredentials, totp: code })
  }

  function handleBackToCredentials() {
    setStep("credentials")
    setCode("")
    mutation.reset()
  }

  // On the OTP step, mfaRequired is always true again (Keycloak still
  // needs a code), so the only real failure there is a wrong/expired
  // code — mutation.data.error covers both steps' generic message.
  const errorMessage = mutation.data?.ok === false && !mutation.data.mfaRequired ? mutation.data.error : null

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background px-5 py-10">
      <div
        className="pointer-events-none fixed inset-0 z-0"
        style={{
          backgroundImage:
            "repeating-linear-gradient(to bottom, transparent, transparent 38px, rgba(31,77,58,0.035) 39px)",
        }}
      />

      <div className="relative z-10 w-full max-w-[404px] rounded-[18px] border border-border bg-card p-9 pb-[34px] shadow-[var(--ledger-shadow)]">
        <div className="mb-[26px] flex flex-col items-center">
          <div className="mb-3 flex size-[46px] items-center justify-center rounded-xl bg-primary shadow-[0_4px_14px_rgba(31,77,58,0.28)]">
            <Layers size={22} color="var(--primary-foreground)" />
          </div>
          <div className="font-heading text-[19px] font-semibold tracking-[-0.01em] text-foreground">
            Ledger
          </div>
          <div className="mt-[5px] font-mono text-[9.5px] tracking-[0.16em] text-[var(--ink-faint)]">
            DOCUMENT VAULT
          </div>
        </div>

        {step === "credentials" ? (
          <>
        <div className="mb-1.5 text-center font-mono text-[10.5px] font-medium tracking-[0.12em] text-[var(--gold)]">
          REALM: DMS-TENANT
        </div>
        <h1 className="mb-[22px] text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
          Sign in to your account
        </h1>

        {errorMessage && (
          <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
            <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
            {errorMessage}
          </div>
        )}

            <form onSubmit={handleSubmit(handleCredentialsSubmit)} noValidate>
          <div className="mb-[17px]">
            <label htmlFor="username" className="mb-[7px] block text-[13px] font-semibold text-foreground">
              Username or email
            </label>
            <input
              id="username"
              type="text"
              placeholder="username"
              autoComplete="username"
              {...register("username")}
              className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] px-[13px] py-[11px] text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
            />
            {credentialErrors.username && (
              <p className="mt-1.5 text-[12px] text-[var(--rust)]">{credentialErrors.username.message}</p>
            )}
          </div>

          <div className="mb-[17px]">
            <label htmlFor="password" className="mb-[7px] block text-[13px] font-semibold text-foreground">
              Password
            </label>
            <div className="relative">
            <input
              id="password"
                type={showPassword ? "text" : "password"}
              placeholder="••••••••••"
              autoComplete="current-password"
              {...register("password")}
                className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] px-[13px] py-[11px] pr-10 text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
            />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                tabIndex={-1}
                aria-label={showPassword ? "Hide password" : "Show password"}
                aria-pressed={showPassword}
                className="absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)] transition-colors hover:text-foreground"
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {credentialErrors.password && (
              <p className="mt-1.5 text-[12px] text-[var(--rust)]">{credentialErrors.password.message}</p>
            )}
          </div>

          <div className="mb-[22px] flex items-center justify-between">
            <label className="flex items-center gap-[7px] text-[13px] text-muted-foreground">
              <input
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                className="size-3.5 accent-primary"
              />
              Remember me
            </label>
            <a href="/reset-password" className="text-[13px] font-semibold text-primary hover:underline">
              Forgot password?
            </a>
          </div>

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527] disabled:cursor-not-allowed disabled:opacity-70"
          >
            {mutation.isPending ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <div className="mt-[26px] mb-[18px] flex items-center gap-3">
          <div className="h-px flex-1 bg-border" />
          <span className="font-mono text-[10px] tracking-[0.1em] whitespace-nowrap text-[var(--ink-faint)]">
            OR CONTINUE WITH
          </span>
          <div className="h-px flex-1 bg-border" />
        </div>

        <button
          type="button"
          className="mb-2.5 flex w-full items-center gap-[11px] rounded-[9px] border border-[var(--border-strong)] bg-card px-3.5 py-2.5 text-[13.5px] font-semibold text-foreground transition-colors hover:bg-[var(--secondary)]"
        >
          <svg className="size-[19px] shrink-0 rounded" viewBox="0 0 48 48">
            <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3c-1.6 4.6-6 8-11.3 8-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34.6 6.1 29.6 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.2-.1-2.4-.4-3.5z" />
            <path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.6 16 19 13 24 13c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34.6 6.1 29.6 4 24 4 16.3 4 9.7 8.3 6.3 14.7z" />
            <path fill="#4CAF50" d="M24 44c5.5 0 10.4-2.1 14.1-5.6l-6.5-5.5C29.6 34.7 26.9 36 24 36c-5.2 0-9.6-3.3-11.2-8l-6.6 5.1C9.6 39.6 16.2 44 24 44z" />
            <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.3-2.2 4.2-4.1 5.6l6.5 5.5C40.5 36.8 44 30.9 44 24c0-1.2-.1-2.4-.4-3.5z" />
          </svg>
          Continue with Google Workspace
        </button>
        <button
          type="button"
          className="flex w-full items-center gap-[11px] rounded-[9px] border border-[var(--border-strong)] bg-card px-3.5 py-2.5 text-[13.5px] font-semibold text-foreground transition-colors hover:bg-[var(--secondary)]"
        >
          <svg className="size-[19px] shrink-0" viewBox="0 0 23 23">
            <rect x="1" y="1" width="10" height="10" fill="#F25022" />
            <rect x="12" y="1" width="10" height="10" fill="#7FBA00" />
            <rect x="1" y="12" width="10" height="10" fill="#00A4EF" />
            <rect x="12" y="12" width="10" height="10" fill="#FFB900" />
          </svg>
          Continue with Microsoft Entra ID
        </button>

        <div className="mt-[26px] text-center text-xs text-[var(--ink-faint)]">
          Need access? Contact your workspace administrator.
        </div>
          </>
        ) : (
          <div className="text-center">
            <div className="mx-auto mb-[18px] flex size-[52px] items-center justify-center rounded-full border border-border bg-primary-tint">
              <ShieldCheck size={24} className="text-primary" />
            </div>
            <h1 className="mb-2 font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Two-factor authentication
            </h1>
            <p className="mb-[26px] text-[13.5px] leading-[1.5] text-muted-foreground">
              Enter the code from your authenticator app
            </p>

            {errorMessage && (
              <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-left text-[13px] leading-[1.4] text-[#7A3623]">
                <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
                {errorMessage}
              </div>
            )}

            <form onSubmit={handleOtpSubmit}>
              <OtpInput value={code} onChange={setCode} disabled={mutation.isPending} />

              <button
                type="submit"
                disabled={mutation.isPending || code.length < 6}
                className="w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527] disabled:cursor-not-allowed disabled:opacity-70"
              >
                {mutation.isPending ? "Verifying…" : "Submit"}
              </button>
            </form>

            <button
              type="button"
              onClick={handleBackToCredentials}
              className="mt-4 text-[13px] font-semibold text-primary hover:underline"
            >
              Back to sign in
            </button>

          </div>
        )}
      </div>
    </div>
  )
}