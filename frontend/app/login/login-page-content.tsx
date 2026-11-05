// app/login/login-page-content.tsx
"use client"

import { useEffect, useState, type FormEvent } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import {
  ArrowRight,
  Layers,
  Lock,
  ShieldCheck,
  User,
} from "lucide-react"
import { toast } from "sonner"

import { LoginHeadline, LoginSpheres } from "@/components/auth/login-hero"
import { OtpInput, OTP_LENGTH } from "@/components/auth/otp-input"
import {
  loginCredentialsSchema,
  type LoginCredentialsValues,
} from "@/components/auth/schema"
import { APP_NAME } from "@/lib/app-config"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

interface LoginResponse {
  ok: boolean
  error?: string
  mfaRequired?: boolean
}

/**
 * Allowed to fail silently and just assume MFA isn't required - a
 * transient Admin API hiccup should degrade to "ask for the password and
 * let the real login attempt be the source of truth," not block the
 * sign-in page outright (mirrors the backend's own
 * `AuthService#isMfaRequired`, which treats any lookup failure the same
 * way).
 */
async function checkMfaRequired(username: string): Promise<boolean> {
  try {
    const body = await apiFetchJson<{ mfaRequired: boolean }>(
      `${API_BASE_URL}/api/v1/auth/mfa-required?username=${encodeURIComponent(username)}`
    )
    return body.mfaRequired
  } catch {
    return false
  }
}

/**
 * Two calls to dms-core, not one — see the component doc comment below
 * for why the MFA check has to happen before the real login attempt
 * rather than being inferred from its result.
 *
 * Deliberately catches its own error and resolves normally (`{ ok: false,
 * error }`) rather than letting a rejected fetch propagate to React
 * Query, same as `checkMfaRequired` above — this is what keeps a
 * wrong-password attempt (`InvalidCredentialsException`, 401) from ever
 * reaching the global session-expiry handler in
 * `components/providers/providers.tsx`, which treats *any* 401 it
 * observes as an expired session. See `isSessionExpired`'s own doc
 * comment for why that's safe: this function is the only thing standing
 * between the two, so don't remove this try/catch.
 */
async function login(
  username: string,
  password: string,
  rememberMe: boolean,
  totp?: string
): Promise<LoginResponse> {
  if (!totp) {
    const mfaRequired = await checkMfaRequired(username)
    if (mfaRequired) {
      return { ok: false, mfaRequired: true }
    }
  }

  try {
    // apiFetch, not apiFetchJson: dms-core's /auth/login responds 200 with
    // an empty body (ResponseEntity<Void>) on success, and response.json()
    // on an empty body throws "Unexpected end of JSON input" - this call
    // only needs apiFetch's status-code error handling, not a parsed body.
    await apiFetch(
      `${API_BASE_URL}/api/v1/auth/login`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password, totp, rememberMe }),
      },
      "Invalid username or password."
    )
  } catch (err) {
    return {
      ok: false,
      error:
        err instanceof Error ? err.message : "Invalid username or password.",
    }
  }
  return { ok: true }
}

const inputClass =
  "w-full rounded-btn border border-transparent bg-[#f0efea] py-3.5 pr-3.5 pl-11 text-sm text-foreground transition-colors outline-none placeholder:text-[var(--ink-faint)] focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"

const primaryButtonClass =
  "group flex w-full items-center justify-center gap-2 rounded-btn border border-[var(--primary-hover)] bg-primary py-3.5 text-sm font-bold tracking-[0.08em] text-primary-foreground uppercase shadow-[0_8px_22px_color-mix(in_srgb,var(--primary)_32%,transparent)] transition-all hover:-translate-y-px hover:bg-[var(--primary-hover)] hover:shadow-[0_12px_28px_color-mix(in_srgb,var(--primary)_40%,transparent)] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-70 disabled:shadow-none"

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
 * Layout: a brand panel beside the form from `lg` up, the form alone
 * (with the logo above it) below that.
 *
 * Rendered inside a <Suspense> boundary by app/login/page.tsx, since
 * useSearchParams() (used for the dev OTP bypass below) requires one.
 */
export function LoginPageContent() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const searchParams = useSearchParams()
  const organisationInactive = searchParams.get("reason") === "organisation-inactive"
  useEffect(() => {
    if (organisationInactive) {
      toast.error("You were signed out because your organisation's account is suspended or closed. Contact your administrator.", {
        id: "organisation-inactive",
      })
    }
  }, [organisationInactive])
  // Dev-only: visiting /login?step=otp lands straight on the OTP screen
  // without needing dms-core's /mfa-required endpoint or Keycloak at all.
  // Stripped out entirely in production builds — see the check below.
  const devOtpBypass =
    process.env.NODE_ENV !== "production" && searchParams.get("step") === "otp"
  const [step, setStep] = useState<"credentials" | "otp">(
    devOtpBypass ? "otp" : "credentials"
  )
  const [rememberMe, setRememberMe] = useState(false)
  const [code, setCode] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  // Kept alongside the validated form state below — the OTP step's retry
  // needs the credentials again, and OtpInput is a controlled component
  // that doesn't fit cleanly into react-hook-form's register() pattern.
  const [savedCredentials, setSavedCredentials] = useState({
    username: "",
    password: "",
  })

  // Validated on submit and on blur, NOT on every change, and the submit
  // button is not gated on validity. Browser autofill (and password
  // managers) fill the fields without firing the change events a form
  // library listens to, so a button gated on `isValid` stayed disabled over
  // a visibly filled form. handleSubmit reads the real field values.
  const {
    register,
    handleSubmit,
    formState: { errors: credentialErrors },
  } = useForm<LoginCredentialsValues>({
    resolver: zodResolver(loginCredentialsSchema),
    defaultValues: { username: "", password: "" },
    mode: "onTouched",
  })

  const mutation = useMutation({
    mutationFn: ({
      username,
      password,
      totp,
    }: {
      username: string
      password: string
      totp?: string
    }) => login(username, password, rememberMe, totp),
    onSuccess: (result) => {
      if (result.ok) {
        // Without this, any "me"-scoped data a PREVIOUS session cached (current user, visual
        // signature, permissions, notifications...) would still be sitting in this tab's React Query
        // cache - a client-side navigation never remounts the Providers tree that owns it, so a
        // different person signing in right after someone else, in the same tab, would briefly see
        // the first person's cached data before anything refetches. router.refresh() only affects
        // Next's server-component cache, not this one.
        queryClient.clear()
        toast.success("Signed in successfully.")
        router.push("/")
        router.refresh()
        return
      }
      if (result.mfaRequired) {
        setStep("otp")
        return
      }
      toast.error(result.error ?? "Invalid username or password.")
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

  return (
    <div className="flex min-h-screen items-center justify-center bg-background bg-[radial-gradient(circle_at_15%_10%,var(--primary-tint),transparent_55%)] lg:p-8 [@media(max-height:760px)]:lg:p-4">
      {/* One rounded card, split: brand panel | form */}
      <div className="grid w-full lg:h-[min(660px,calc(100svh-2rem))] lg:min-h-[500px] lg:max-w-[1120px] lg:grid-cols-2 lg:overflow-hidden lg:rounded-[28px] lg:bg-card lg:shadow-[0_30px_80px_color-mix(in_srgb,var(--primary)_22%,transparent)] lg:ring-1 lg:ring-border">
        {/* Brand panel: wide screens only */}
        <aside
          aria-hidden="true"
          className="relative hidden flex-col overflow-hidden bg-[linear-gradient(150deg,var(--primary)_0%,var(--primary-hover)_100%)] p-10 text-primary-foreground lg:flex xl:p-12 [@media(max-height:760px)]:lg:p-7"
        >
          {/* Oak-leaf photo, tinted into the brand green and drifting slowly */}
          <div
            className="pointer-events-none absolute inset-0 bg-cover bg-center opacity-[0.14] mix-blend-luminosity"
            style={{ backgroundImage: "url('/images/oak-leaves.jpg')" }}
          />
          {/* Bobbing spheres */}
          <LoginSpheres />
          {/* Aurora glow */}
          <div className="pointer-events-none absolute top-1/3 -left-24 size-[420px] rounded-full bg-[radial-gradient(closest-side,rgb(185_138_62/0.25),transparent)]" />

          <div className="relative flex items-center gap-3">
            <div className="flex size-11 items-center justify-center rounded-xl bg-white/15 ring-1 ring-white/25">
              <Layers size={22} />
            </div>
            <div>
              <div className="font-heading text-lg font-semibold tracking-[-0.01em]">
                {APP_NAME}
              </div>
              <div className="font-mono text-[9.5px] tracking-[0.16em] opacity-70">
                DOCUMENT VAULT
              </div>
            </div>
          </div>

          <div className="relative my-auto py-8">
            <LoginHeadline appName={APP_NAME} />
          </div>
        </aside>

        {/* Form panel */}
        <main className="relative flex items-center justify-center overflow-hidden bg-card px-5 py-10 sm:px-10 lg:px-12 xl:px-16">
          {/* Corner sphere, echoing the brand panel */}
          <div
            aria-hidden="true"
            className="lg-bob pointer-events-none absolute -right-20 -bottom-24 hidden size-[220px] rounded-full opacity-90 shadow-[inset_-10px_-14px_36px_rgb(0_0_0/0.3)] lg:block"
            style={{
              background:
                "radial-gradient(circle at 30% 24%, rgb(255 255 255 / 0.3), rgb(255 255 255 / 0) 42%), linear-gradient(150deg, #2f7557 0%, #1f4d3a 52%, #123527 100%)",
              animationDuration: "20s",
            }}
          />

          <div className="relative w-full max-w-[400px] animate-[lg-rise_0.7s_cubic-bezier(0.2,0.7,0.2,1)_both] motion-reduce:animate-none">
            {/* Compact brand banner: replaces the brand panel below `lg` */}
            <div className="relative mb-8 overflow-hidden rounded-lg bg-[linear-gradient(150deg,var(--primary)_0%,var(--primary-hover)_100%)] p-5 text-primary-foreground sm:p-6 lg:hidden">
              <div
                aria-hidden="true"
                className="pointer-events-none absolute -top-16 -right-16 size-52 rounded-full bg-[radial-gradient(closest-side,rgb(255_255_255/0.2),transparent)]"
              />
              <div className="relative flex items-center gap-3">
                <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/15 ring-1 ring-white/25">
                  <Layers size={20} />
                </div>
                <div className="min-w-0">
                  <div className="truncate font-heading text-lg font-semibold tracking-[-0.01em]">
                    {APP_NAME}
                  </div>
                  <div className="font-mono text-[9.5px] tracking-[0.16em] opacity-70">
                    DOCUMENT VAULT
                  </div>
                </div>
              </div>
              <p className="relative mt-4 text-[13px] leading-[1.5] opacity-85">
                Your documents, organised and under control.
              </p>
            </div>

            {step === "credentials" ? (
              <>
                <h1 className="font-heading text-[40px] leading-none font-extrabold tracking-tight text-foreground">
                  Sign in
                </h1>
                <p className="mt-3 mb-8 text-sm text-muted-foreground">
                  Welcome back. Sign in to continue to {APP_NAME}.
                </p>

                <form
                  onSubmit={handleSubmit(handleCredentialsSubmit)}
                  noValidate
                  className="space-y-5"
                >
                  <div>
                    <label
                      htmlFor="username"
                      className="mb-2 block text-[13px] font-semibold text-foreground"
                    >
                      Username or email
                    </label>
                    <div className="relative">
                      <User
                        size={16}
                        className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-foreground"
                      />
                      <input
                        id="username"
                        type="text"
                        placeholder="you@company.com"
                        autoComplete="username"
                        aria-invalid={
                          credentialErrors.username ? true : undefined
                        }
                        {...register("username")}
                        className={inputClass}
                      />
                    </div>
                    {credentialErrors.username && (
                      <p className="mt-1.5 text-[12px] text-[var(--rust)]">
                        {credentialErrors.username.message}
                      </p>
                    )}
                  </div>

                  <div>
                    <label
                      htmlFor="password"
                      className="mb-2 block text-[13px] font-semibold text-foreground"
                    >
                      Password
                    </label>
                    <div className="relative">
                      <Lock
                        size={16}
                        className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-foreground"
                      />
                      <input
                        id="password"
                        type={showPassword ? "text" : "password"}
                        placeholder="Enter your password"
                        autoComplete="current-password"
                        aria-invalid={
                          credentialErrors.password ? true : undefined
                        }
                        {...register("password")}
                        className={`${inputClass} pr-11`}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword((prev) => !prev)}
                        tabIndex={-1}
                        aria-label={
                          showPassword ? "Hide password" : "Show password"
                        }
                        aria-pressed={showPassword}
                        className="absolute top-1/2 right-4 -translate-y-1/2 text-[11px] font-bold tracking-[0.1em] text-primary uppercase transition-colors hover:text-[var(--primary-hover)]"
                      >
                        {showPassword ? "Hide" : "Show"}
                      </button>
                    </div>
                    {credentialErrors.password && (
                      <p className="mt-1.5 text-[12px] text-[var(--rust)]">
                        {credentialErrors.password.message}
                      </p>
                    )}
                  </div>

                  <div className="flex items-center justify-between">
                    <label className="flex items-center gap-2 text-[13px] text-muted-foreground">
                      <input
                        type="checkbox"
                        checked={rememberMe}
                        onChange={(e) => setRememberMe(e.target.checked)}
                        className="size-3.5 accent-primary"
                      />
                      Remember me
                    </label>
                    <a
                      href="/reset-password"
                      className="text-[13px] font-semibold text-primary hover:underline"
                    >
                      Forgot password?
                    </a>
                  </div>

                  <button
                    type="submit"
                    disabled={mutation.isPending}
                    className={primaryButtonClass}
                  >
                    {mutation.isPending ? (
                      "Signing in…"
                    ) : (
                      <>
                        Sign in
                        <ArrowRight
                          size={16}
                          className="transition-transform group-hover:translate-x-0.5"
                        />
                      </>
                    )}
                  </button>
                </form>

                <p className="mt-8 text-center text-xs text-[var(--ink-faint)]">
                  Need access? Contact your workspace administrator.
                </p>
              </>
            ) : (
              <div>
                <div className="bg-primary-tint mb-5 flex size-[52px] items-center justify-center rounded-full border border-border">
                  <ShieldCheck size={24} className="text-primary" />
                </div>
                <h1 className="font-heading text-[28px] font-semibold tracking-[-0.02em] text-foreground">
                  Two-factor authentication
                </h1>
                <p className="mt-1.5 mb-8 text-sm text-muted-foreground">
                  Enter the code from your authenticator app.
                </p>

                <form onSubmit={handleOtpSubmit}>
                  <OtpInput
                    value={code}
                    onChange={setCode}
                    disabled={mutation.isPending}
                  />

                  <button
                    type="submit"
                    disabled={mutation.isPending || code.length < OTP_LENGTH}
                    className={primaryButtonClass}
                  >
                    {mutation.isPending ? "Verifying…" : "Submit"}
                  </button>
                </form>

                <button
                  type="button"
                  onClick={handleBackToCredentials}
                  className="mt-5 text-[13px] font-semibold text-primary hover:underline"
                >
                  Back to sign in
                </button>
              </div>
            )}
          </div>
        </main>
      </div>
    </div>
  )
}
