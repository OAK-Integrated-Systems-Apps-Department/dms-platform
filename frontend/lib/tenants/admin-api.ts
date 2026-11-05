import { API_BASE_URL } from "@/lib/api-config"
import { confirmationHeaders, type PasswordConfirmation } from "@/lib/auth/password-confirmation"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/tenants`

/** Mirrors TenantResponse, as platform admins see it. */
export interface OrganisationDto {
  tenantId: string
  tenantName: string
  domain: string
  subscriptionTier: string
  /** active | suspended | archived */
  status: string
  createdAt: string
  updatedAt: string | null
  /** removed - can be reactivated, or its keys destroyed */
  deactivated: boolean
  deactivatedAt: string | null
  /** set once its encryption keys were destroyed - final */
  keysShreddedAt: string | null
}

interface Page<T> {
  content: T[]
  totalElements: number
}

/** Every organisation, deactivated ones too. Platform admins only. */
export async function fetchOrganisations(): Promise<OrganisationDto[]> {
  const page = await apiFetchJson<Page<OrganisationDto>>(
    `${BASE}?includeDeactivated=true&size=500&sort=tenantName,asc`,
    undefined,
    "Could not load the organisations."
  )
  return page.content
}

/** What onboarding a new client needs: the organisation and its first administrator. */
export interface ProvisionOrganisationRequest {
  tenantName: string
  /** Lowercase hostname, e.g. "acme.oakdms.com" - it doubles as the login domain. */
  domain: string
  subscriptionTier: string
  adminEmail: string
  adminUsername: string
  adminFirstName: string
  adminLastName?: string
  /** More administrators created with the organisation (up to 9). Each is invited by email. */
  additionalAdmins?: {
    email: string
    username: string
    firstName: string
    lastName?: string
  }[]
}

export interface ProvisionOrganisationResult {
  tenant: OrganisationDto
  admin: { userId: string; email: string; username: string }
  additionalAdmins: { userId: string; email: string; username: string }[]
}

/**
 * Onboards a client: creates the organisation AND its first TENANT_ADMIN in
 * one atomic call, and emails them to set a password.
 *
 * Deliberately this rather than `POST /api/v1/tenants`, which makes an
 * organisation nobody can log into - there is no other endpoint that can
 * create a user outside the caller's own tenant or grant TENANT_ADMIN, so a
 * plain create leaves an organisation that has to be finished by hand in
 * Keycloak.
 */
export async function provisionOrganisation(
  confirm: PasswordConfirmation,
  request: ProvisionOrganisationRequest
): Promise<ProvisionOrganisationResult> {
  return apiFetchJson(
    `${BASE}/provision`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify(request),
    },
    "Could not create the organisation."
  )
}

/**
 * Renames an organisation or changes its tier. `status` is sent unchanged -
 * suspending and resuming go through `setOrganisationStatus` instead, so an
 * edit can never alter the lifecycle by accident.
 */
export async function updateOrganisation(
  confirm: PasswordConfirmation,
  org: OrganisationDto,
  changes: { tenantName: string; subscriptionTier: string }
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${org.tenantId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify({
        tenantName: changes.tenantName,
        subscriptionTier: changes.subscriptionTier,
        status: org.status,
      }),
    },
    "Could not update the organisation."
  )
}

/** Suspend, resume or archive - name and tier are sent back unchanged. */
export async function setOrganisationStatus(
  confirm: PasswordConfirmation,
  org: OrganisationDto,
  status: "active" | "suspended" | "archived"
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${org.tenantId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify({
        tenantName: org.tenantName,
        subscriptionTier: org.subscriptionTier,
        status,
      }),
    },
    "Could not change the organisation's status."
  )
}

export async function deactivateOrganisation(
  confirm: PasswordConfirmation,
  tenantId: string
): Promise<void> {
  await apiFetch(
    `${BASE}/${tenantId}`,
    { method: "DELETE", headers: confirmationHeaders(confirm) },
    "Could not deactivate the organisation."
  )
}

export async function reactivateOrganisation(
  tenantId: string
): Promise<OrganisationDto> {
  return apiFetchJson(
    `${BASE}/${tenantId}/reactivate`,
    { method: "POST" },
    "Could not reactivate the organisation."
  )
}

/** Crypto-shredding: destroys its keys - its encrypted documents can never be read again. */
export async function destroyOrganisationKeys(
  confirm: PasswordConfirmation,
  tenantId: string,
  confirmTenantName: string,
  reason: string,
  acknowledgeUnencrypted: boolean
): Promise<{ wrappedKeysDeleted: number; unencryptedFilesLeft: number }> {
  return apiFetchJson(
    `${BASE}/${tenantId}/shred-keys`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify({
        confirmTenantName,
        reason,
        acknowledgeUnencrypted,
      }),
    },
    "Could not destroy the organisation's keys."
  )
}

/** The organisations the platform administrator has allowed to use online editing (`GET /tenants/online-editing`). */
export async function fetchOnlineEditingAllowed(): Promise<string[]> {
  const result = await apiFetchJson<{ tenantIds: string[] }>(
    `${BASE}/online-editing`,
    undefined,
    "Could not load which organisations can use online editing."
  )
  return result.tenantIds
}

/**
 * Allows or stops online editing for one organisation (`PUT /tenants/{id}/online-editing`). While it is allowed the
 * organisation's own administrator decides for its people; while it isn't, nobody there gets the editor and its
 * settings page is not shown. Needs the signed-in platform administrator's own password.
 */
export async function setOnlineEditingAllowed(
  confirm: PasswordConfirmation,
  tenantId: string,
  allowed: boolean
): Promise<{ tenantId: string; allowed: boolean }> {
  return apiFetchJson(
    `${BASE}/${tenantId}/online-editing`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify({ allowed }),
    },
    "Could not change online editing for the organisation."
  )
}

/** Mirrors TenantAdminResponse: one administrator of an organisation. */
export interface TenantAdminDto {
  userId: string
  username: string
  email: string
  firstName: string
  lastName: string | null
  displayName: string
  /** active | locked | disabled */
  status: string
  /** they have set their own password (accepted the invitation) */
  verified: boolean
  lastLoginAt: string | null
  createdAt: string
}

/** The people who administer an organisation - platform admins only. */
export async function fetchTenantAdmins(
  tenantId: string
): Promise<TenantAdminDto[]> {
  return apiFetchJson(
    `${BASE}/${tenantId}/admins`,
    undefined,
    "Could not load the organisation's administrators."
  )
}

/**
 * Corrects an administrator's name, email or username. Two accounts can't share an email or a username (409
 * EMAIL_IN_USE / USERNAME_IN_USE); a new username applies from their next sign-in.
 */
export async function updateTenantAdmin(
  confirm: PasswordConfirmation,
  tenantId: string,
  userId: string,
  changes: {
    firstName: string
    lastName: string
    email: string
    username: string
  }
): Promise<TenantAdminDto> {
  return apiFetchJson(
    `${BASE}/${tenantId}/admins/${userId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json", ...confirmationHeaders(confirm) },
      body: JSON.stringify(changes),
    },
    "Could not update the administrator."
  )
}

/** Sends the invitation email again, to an administrator who has not set a password yet. */
export async function resendTenantAdminInvite(
  tenantId: string,
  userId: string
): Promise<void> {
  await apiFetch(
    `${BASE}/${tenantId}/admins/${userId}/resend-invite`,
    { method: "POST" },
    "Could not send the invitation."
  )
}
