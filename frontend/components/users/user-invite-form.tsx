"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { AlertTriangle, CheckCircle2 } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { API_BASE_URL } from "@/lib/api-config"

import { userInviteFormSchema, type UserInviteFormValues } from "./schema"

/**
 * Add User form. Only first name, last name, and email — no password
 * field. This is an invite, not a signup: the account is created
 * server-side (dms-core → Keycloak) with a random temporary password, and
 * the new user gets an email with a link to verify their address and set
 * their own password (see app/update-password/page.tsx). Nothing about
 * this page is reachable without an existing admin session — self
 * registration doesn't happen anywhere in this app.
 */
export function UserInviteForm() {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [invitedEmail, setInvitedEmail] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<UserInviteFormValues>({
    resolver: zodResolver(userInviteFormSchema),
    defaultValues: { firstName: "", lastName: "", email: "" },
  })

  async function onSubmit(values: UserInviteFormValues) {
    setSubmitError(null)
    const response = await fetch(`${API_BASE_URL}/api/v1/users`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({
        email: values.email,
        displayName: `${values.firstName} ${values.lastName}`.trim(),
      }),
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      setSubmitError(
        response.status === 403
          ? "You don't have permission to invite users."
          : (body?.message ?? "Could not send the invitation.")
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
          Invitation sent to <span className="font-semibold">{invitedEmail}</span>. They&apos;ll receive
          an email to verify their address and set a password.
        </p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            onClick={() => setInvitedEmail(null)}
            className="rounded-[9px] border border-[var(--border-strong)] px-4 py-2 text-[13px] font-semibold text-foreground"
          >
            Invite another
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
    <form onSubmit={handleSubmit(onSubmit)} className="rounded-[14px] border border-border bg-card p-6">
      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
          <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
          {submitError}
        </div>
      )}

      <div className="mb-4 grid grid-cols-2 gap-4">
        <div>
          <Label htmlFor="firstName">First name</Label>
          <Input id="firstName" placeholder="Jane" autoComplete="given-name" {...register("firstName")} />
          {errors.firstName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.firstName.message}</p>
          )}
        </div>
        <div>
          <Label htmlFor="lastName">Last name</Label>
          <Input id="lastName" placeholder="Perera" autoComplete="family-name" {...register("lastName")} />
          {errors.lastName && (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.lastName.message}</p>
          )}
        </div>
      </div>

      <div className="mb-6">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          placeholder="jane.perera@yourcompany.com"
          autoComplete="email"
          {...register("email")}
        />
        {errors.email && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.email.message}</p>}
      </div>

      <div className="flex justify-end gap-3">
        <button
          type="button"
          onClick={() => router.push("/admin/users")}
          className="rounded-[9px] border border-[var(--border-strong)] px-4 py-2 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-[9px] border border-[#123527] bg-primary px-4 py-2 text-[13px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Sending…" : "Send invitation"}
        </button>
      </div>
    </form>
  )
}