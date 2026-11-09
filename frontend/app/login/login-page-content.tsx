// app/login/login-page-content.tsx
"use client"

import { useEffect, useState, type FormEvent } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import {
  ArrowRight,
  Eye,
  EyeOff,
  Lock,
  ShieldCheck,
  User,
} from "lucide-react"
import { toast } from "sonner"

import {
  AuthShell,
  authButtonClass,
  authFieldClass,
  authInputClass,
  authInputIconClass,
  authInputToggleClass,
} from "@/components/auth/auth-shell"
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

const inputClass = authInputClass
const primaryButtonClass = authButtonClass

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
 * Layout: a brand panel beside the form from `lg` up. Below that, the
 * phone layout: the oak-leaf photo across the top, a wave of white rising
 * over it, and the form centred on the white below.
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
    <AuthShell>
      {step === "credentials" ? (
        <>
          <h1 className="text-center font-heading text-[28px] leading-none font-extrabold tracking-tight text-foreground max-lg:text-primary sm:text-[40px] lg:text-left">
            <span className="lg:hidden">Welcome Back</span>
            <span className="hidden lg:inline">Sign in</span>
          </h1>
          <p className="mt-2 mb-6 text-center text-[13px] text-muted-foreground sm:mt-3 sm:mb-8 sm:text-sm lg:text-left">
            <span className="lg:hidden">Sign in to your account.</span>
            <span className="hidden lg:inline">
              Welcome back. Sign in to continue to {APP_NAME}.
            </span>
          </p>

          <form
            onSubmit={handleSubmit(handleCredentialsSubmit)}
            noValidate
            className="space-y-3 sm:space-y-5"
          >
            <div>
              <label
                htmlFor="username"
                className="mb-1 block text-[12.5px] font-semibold text-foreground max-lg:sr-only sm:mb-2 sm:text-[13px]"
              >
                Username or email
              </label>
              <div className={authFieldClass}>
                <User size={16} className={authInputIconClass} />
                <input
                  id="username"
                  type="text"
                  placeholder="Username or email"
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
                className="mb-1 block text-[12.5px] font-semibold text-foreground max-lg:sr-only sm:mb-2 sm:text-[13px]"
              >
                Password
              </label>
              <div className={authFieldClass}>
                <Lock size={16} className={authInputIconClass} />
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  placeholder="Password"
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
                  className={authInputToggleClass}
                >
                  <span className="text-[11px] font-bold tracking-[0.1em] uppercase max-lg:hidden">
                    {showPassword ? "Hide" : "Show"}
                  </span>
                  <span className="lg:hidden">
                    {showPassword ? (
                      <EyeOff size={18} />
                    ) : (
                      <Eye size={18} />
                    )}
                  </span>
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
                    className="transition-transform group-hover:translate-x-0.5 max-lg:hidden"
                  />
                </>
              )}
            </button>
          </form>

          <p className="mt-4 text-center text-[11.5px] text-[var(--ink-faint)] sm:mt-8 sm:text-xs">
            Need access? Contact your workspace administrator.
          </p>
        </>
      ) : (
        <div>
          <div className="bg-primary-tint mb-3 flex size-11 sm:mb-5 sm:size-[52px] items-center justify-center rounded-full border border-border">
            <ShieldCheck size={24} className="text-primary" />
          </div>
          <h1 className="font-heading text-[22px] font-semibold tracking-[-0.02em] sm:text-[28px] text-foreground">
            Two-factor authentication
          </h1>
          <p className="mt-1.5 mb-5 text-[13px] text-muted-foreground sm:mb-8 sm:text-sm">
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
    </AuthShell>
  )
}
