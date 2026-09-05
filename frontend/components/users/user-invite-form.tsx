"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, CheckCircle2, ShieldCheck } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { initialRoles } from "@/components/roles/data"
import { API_BASE_URL } from "@/lib/api-config"

import { RoleMultiSelect } from "./role-multi-select"
import { userInviteFormSchema, type UserInviteFormValues } from "./schema"

const ROLE_OPTIONS = initialRoles.map((role) => role.name)

/**
 * Add User form, per dms-add-user.html. Still an invite, not a signup —
 * no password field: the account is created server-side (dms-core →
 * Keycloak) with a random temporary password, and the new user gets an
 * email with a link to verify their address and set their own password
 * (see app/update-password/page.tsx). Nothing here is reachable without
 * an existing admin session.
 *
 * KNOWN GAP: dms-core's CreateUserRequest only accepts
 * {email, firstName, lastName} (see
 * backend/.../user/dto/CreateUserRequest.java). `username`, `roles`, and
 * `additionalProfile` are captured here and sent in the request body below,
 * but the backend will silently ignore them until CreateUserRequest,
 * UserServiceImpl, and KeycloakUserService are extended to read and act on
 * them (setting the Keycloak username distinctly from email, assigning
 * realm roles, storing profile notes). The form works end-to-end for
 * name/email/invite-email today; role assignment and the username/profile
 * fields need that backend work before they take effect.
 */
export function UserInviteForm() {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [invitedEmail, setInvitedEmail] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<UserInviteFormValues>({
    resolver: zodResolver(userInviteFormSchema),
    defaultValues: { firstName: "", lastName: "", username: "", email: "", roles: [], additionalProfile: "" },
  })

  async function onSubmit(values: UserInviteFormValues) {
    setSubmitError(null)
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/users`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({
        email: values.email,
        firstName: values.firstName,
        lastName: values.lastName,
        // Sent for when the backend is extended to read them — see the
        // KNOWN GAP note above. Harmless to send today; dms-core just
        // won't act on them yet.
        username: values.username,
        roles: values.roles,
        additionalProfile: values.additionalProfile || undefined,
      }),
    })
    } catch {
      // fetch() throws when the request never reached a server at all —
      // e.g. the backend isn't running yet. Distinct from a 4xx/5xx
      // below, which means the backend WAS reached and responded.
      setSubmitError("Could not reach the server. Check that the backend is running and try again.")
      return
    }
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      setSubmitError(
        response.status === 403
          ? "You don't have permission to invite users."
          : (body?.message ?? "Could not create the user.")
      )
      return
    }
    setInvitedEmail(values.email)
    reset()
  }

  if (invitedEmail) {
    return (
      <div className="rounded-[14px] border border-primary/30 bg-primary-tint p-6 text-center">
        <CheckCircle2 size={26} className="mx-auto mb-3 text-primary" />
        <p className="mb-4 text-[13.5px] leading-[1.6] text-foreground">
          User created. An email has been sent to <span className="font-semibold">{invitedEmail}</span> to
          verify their address and set a password.
        </p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            onClick={() => setInvitedEmail(null)}
            className="rounded-[9px] border border-[var(--border-strong)] px-4 py-2 text-[13px] font-semibold text-foreground"
          >
            Add another user
          </button>
          <button
            type="button"
            onClick={() => router.push("/admin/users")}
            className="rounded-[9px] border border-primary bg-primary px-4 py-2 text-[13px] font-semibold text-primary-foreground"
          >
            Back to user list
          </button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="rounded-[14px] border border-border bg-card p-[30px] pb-[26px]">

      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
          <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
          {submitError}
        </div>
      )}

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="firstName">First name</Label>
          <Input id="firstName" placeholder="Jane" autoComplete="off" {...register("firstName")} />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.firstName.message}</p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input id="lastName" placeholder="Perera" autoComplete="off" {...register("lastName")} />
          {errors.lastName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.lastName.message}</p>
          )}
        </div>
      </div>

      <div className="mb-5 grid grid-cols-2 gap-[18px]">
        <div>
          <Label htmlFor="username">Username / login ID</Label>
          <Input id="username" placeholder="jane.perera" autoComplete="off" {...register("username")} />
          {errors.username && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.username.message}</p>
          )}
        </div>
        <div>
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          placeholder="jane.perera@yourcompany.com"
            autoComplete="off"
          {...register("email")}
        />
        {errors.email && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.email.message}</p>}
      </div>
      </div>

      <div className="mb-5">
        <Label>Initial role(s)</Label>
        <Controller
          name="roles"
          control={control}
          render={({ field }) => (
            <RoleMultiSelect options={ROLE_OPTIONS} selected={field.value} onChange={field.onChange} />
          )}
        />
        {errors.roles && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.roles.message}</p>}
      </div>

      <div className="mb-5">
        <Label className="mb-[7px] inline-flex items-center gap-2 normal-case">
          <span className="text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase">
            Additional profile fields
          </span>
          <span className="rounded-full border border-[var(--border-strong)] px-2 py-0.5 text-[10px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
            Optional / config-dependent
          </span>
        </Label>
        <Textarea
          placeholder="e.g. department, job title, phone"
          {...register("additionalProfile")}
        />
        {errors.additionalProfile && (
          <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.additionalProfile.message}</p>
        )}
      </div>

      <hr className="my-5 border-t border-dashed border-border" />

      <div className="flex justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/users")}
          className="rounded-[9px] border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-[9px] border border-[#123527] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Creating…" : "Create user"}
        </button>
      </div>
    </form>
  )
}