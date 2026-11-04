"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm, useWatch } from "react-hook-form"
import { AlertTriangle, CheckCircle2 } from "lucide-react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { API_BASE_URL } from "@/lib/api-config"
import { ApiError } from "@/lib/api-error"
import { apiFetchJson } from "@/lib/api-fetch"
import { assignUserRole, isEmailAvailable } from "@/lib/users/api"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useRolesStore } from "@/stores/roles-store"

import { RoleMultiSelect } from "./role-multi-select"
import { userInviteFormSchema, type UserInviteFormValues } from "./schema"

const EMAIL_TAKEN = "This email is already used by another account."

/**
 * Add User form, per dms-add-user.html. Still an invite, not a signup —
 * no password field: the account is created server-side (dms-core →
 * Keycloak) with a random temporary password, and the new user gets an
 * email with a link to verify their address and set their own password
 * (see app/update-password/page.tsx). Nothing here is reachable without
 * an existing admin session.
 *
 * Role options come from the real tenant role list (`useRolesStore`).
 *
 * `CreateUserRequest` only accepts {email, firstName, lastName} — it has
 * no `roles` field. Role assignment is a genuinely separate API
 * (`POST /api/v1/users/{userId}/roles`, see `UserRoleController`) that
 * needs a `userId`, which doesn't exist until the user itself is created —
 * so this creates the user first, then assigns each selected role as a
 * second step once a real `userId` is available. Each assignment is
 * tenant-scoped and mirrored into Keycloak as a realm role by the backend
 * (see `UserRoleServiceImpl.assignRole`), not just written to the local
 * database.
 *
 * `username` is sent as the account's real login id, distinct from
 * `email` — see `CreateUserRequest.username()`'s own doc comment on the
 * backend. It becomes the actual Keycloak username (not a copy of the
 * email the way it used to be created), and is stored on the local
 * `users` row too, so every screen that displays it afterward
 * (`toUserRecord` in `lib/users/api.ts`) reads the real value instead of
 * deriving a placeholder from the email's local part.
 */
export function UserInviteForm() {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [invitedEmail, setInvitedEmail] = useState<string | null>(null)

  const roles = useRolesStore((state) => state.roles)
  const rolesLoading = useRolesStore((state) => state.loading)
  const rolesError = useRolesStore((state) => state.error)
  const fetchRoles = useRolesStore((state) => state.fetchRoles)
  const { hasAnyPermission } = usePermissions()
  const canViewRoles = hasAnyPermission(
    PermissionCodes.ASM_ROLES_VIEW_ROLE,
    PermissionCodes.ASM_ROLES_UPDATE_ROLE
  )
  useEffect(() => {
    // GET /api/v1/roles requires asm.manage-roles-permissions.view-role or
    // .update-role — otua.manage-users.* alone (what actually gates
    // reaching this form) doesn't imply it, so skip the call entirely for
    // a caller without either, rather than 403 and, since nothing here
    // marks that permanent otherwise, keep re-firing it on every remount
    // for the rest of the session.
    if (canViewRoles && roles.length === 0 && !rolesError) fetchRoles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canViewRoles])
  // Only active roles can be newly assigned (see the backend guard in
  // UserRoleServiceImpl#assignRole) — filtering here means the picker
  // simply doesn't offer an inactive one, rather than letting someone
  // pick it and then showing a rejection after the fact.
  const ROLE_OPTIONS = roles.filter((role) => role.status === "Active").map((role) => role.name)

  const {
    register,
    handleSubmit,
    control,
    reset,
    setError,
    clearErrors,
    formState: { errors, isSubmitting, isValid },
  } = useForm<UserInviteFormValues>({
    resolver: zodResolver(userInviteFormSchema),
    defaultValues: { firstName: "", lastName: "", username: "", email: "", roles: [] },
    // Live feedback as the caller types past a field's length limit (or
    // fixes any other validation error), rather than only surfacing it on
    // submit - matters most here since firstName/lastName/username all
    // have a length cap the caller has no other way to see coming.
    mode: "onChange",
  })

  // The email is checked shortly after typing stops - no other user in the organisation, and no
  // account that signs in with it, may have it. The same check the server makes on create.
  const email = useWatch({ control, name: "email" })
  const emailCheck = useRef(0)
  const [checkingFor, setCheckingFor] = useState<string | null>(null)
  const candidateEmail = email?.trim() ?? ""
  const checkingEmail = candidateEmail !== "" && checkingFor === candidateEmail
  useEffect(() => {
    const candidate = email?.trim() ?? ""
    const ticket = ++emailCheck.current
    if (!candidate || !userInviteFormSchema.shape.email.safeParse(candidate).success) return
    const timer = setTimeout(async () => {
      setCheckingFor(candidate)
      try {
        const available = await isEmailAvailable(candidate)
        if (ticket !== emailCheck.current) return
        if (available) clearErrors("email")
        else setError("email", { type: "taken", message: EMAIL_TAKEN })
      } catch {
        // Can't check now - creating the user checks again anyway.
      } finally {
        if (ticket === emailCheck.current) setCheckingFor(null)
      }
    }, 500)
    return () => clearTimeout(timer)
  }, [email, setError, clearErrors])

  async function onSubmit(values: UserInviteFormValues) {
    setSubmitError(null)
    let created: { userId: string }
    try {
      created = await apiFetchJson(
        `${API_BASE_URL}/api/v1/users`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            email: values.email,
            username: values.username,
            firstName: values.firstName,
            lastName: values.lastName,
          }),
        },
        "Could not create the user."
      )
    } catch (err) {
      // Already someone else's - say so under the field it's about.
      if (err instanceof ApiError && err.code === "EMAIL_IN_USE") {
        setError("email", { type: "taken", message: EMAIL_TAKEN }, { shouldFocus: true })
        return
      }
      if (err instanceof ApiError && err.code === "USERNAME_IN_USE") {
        setError("username", { type: "taken", message: err.message }, { shouldFocus: true })
        return
      }
      const message =
        err instanceof ApiError && err.status === 403
          ? "You don't have permission to invite users."
          : err instanceof Error
            ? err.message
            : "Could not create the user."
      setSubmitError(message)
      toast.error(message)
      return
    }

    if (values.roles.length > 0) {
      const roleIdByName = new Map(roles.map((r) => [r.name, r.id]))
      const results = await Promise.allSettled(
        values.roles.map((name) => {
          const roleId = roleIdByName.get(name)
          if (!roleId) {
            return Promise.reject(new Error(`"${name}" isn't in the current role list.`))
          }
          return assignUserRole(created.userId, roleId)
        })
      )
      const failures = results.filter(
        (r): r is PromiseRejectedResult => r.status === "rejected"
      )
      if (failures.length > 0) {
        // The user genuinely exists at this point — don't leave the form
        // implying creation failed. Explain the partial outcome instead,
        // and point at where to finish the job, same tradeoff used when
        // creating a role with permissions pre-selected (see
        // stores/roles-store.ts's addRole).
        const message = `${values.email} was created, but ${failures.length === values.roles.length ? "no roles could" : "some roles could not"} be assigned. Finish assigning roles from their Edit page.`
        setSubmitError(message)
        toast.error(message)
        setInvitedEmail(values.email)
        reset()
        return
      }
    }

    toast.success("User created.")
    setInvitedEmail(values.email)
    reset()
  }

  if (invitedEmail) {
    return (
      <div className="rounded-lg border border-primary/30 bg-primary-tint p-6 text-center">
        <CheckCircle2 size={26} className="mx-auto mb-3 text-primary" />
        <p className="mb-4 text-[13.5px] leading-[1.6] text-foreground">
          User created. An email has been sent to <span className="font-semibold">{invitedEmail}</span> to
          verify their address and set a password.
        </p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            onClick={() => setInvitedEmail(null)}
            className="border border-[var(--border-strong)] font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
          >
            Add another user
          </button>
          <button
            type="button"
            onClick={() => router.push("/admin/users")}
            className="border border-primary bg-primary font-semibold text-primary-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
          >
            Back to user list
          </button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="rounded-lg border border-border bg-card p-[30px] pb-[26px]">

      {submitError && (
        <div className="mb-5 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
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
          {errors.email ? (
            <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.email.message}</p>
          ) : checkingEmail ? (
            <p className="mt-1.5 text-[12px] text-muted-foreground">Checking the email…</p>
          ) : null}
        </div>
      </div>

      <div className="mb-5">
        <Label>Initial role(s)</Label>
        {canViewRoles ? (
          <Controller
            name="roles"
            control={control}
            render={({ field }) => (
              <RoleMultiSelect
                options={ROLE_OPTIONS}
                selected={field.value}
                onChange={field.onChange}
                loading={rolesLoading && roles.length === 0}
              />
            )}
          />
        ) : (
          <p className="mt-1.5 text-[12.5px] text-muted-foreground">
            You don&apos;t have permission to view the role catalog, so roles
            can&apos;t be assigned here. The user can still be invited without
            one.
          </p>
        )}
        {errors.roles && <p className="mt-1.5 text-[12px] text-[var(--rust)]">{errors.roles.message}</p>}
      </div>

      <hr className="my-5 border-t border-dashed border-border" />

      <div className="flex justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/users")}
          className="border border-[var(--border-strong)] font-semibold text-foreground h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting || !isValid || checkingEmail || errors.email?.type === "taken"}
          className="rounded-btn border border-[var(--primary-hover)] bg-primary inline-flex h-[38px] items-center justify-center px-4 text-[13px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
        >
          {isSubmitting ? "Creating…" : "Create user"}
        </button>
      </div>
    </form>
  )
}