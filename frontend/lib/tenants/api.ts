import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's TenantResponse DTO (only the fields the UI uses). */
export interface TenantResponseDto {
  tenantId: string
  tenantName: string
  domain: string
  subscriptionTier: string
  status: string
  createdAt: string
  updatedAt: string | null
}

/**
 * GET /api/v1/tenants/{tenantId}. Allowed for PLATFORM_ADMIN or any user in
 * that same tenant (see TenantSecurity#isSameTenant on the backend), so a
 * regular end-user can resolve their own tenantId to a display name on
 * My Profile without needing admin rights.
 */
export async function fetchTenant(tenantId: string): Promise<TenantResponseDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/tenants/${tenantId}`, undefined, "Could not load tenant details.")
}