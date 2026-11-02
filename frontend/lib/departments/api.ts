import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

import type { LookupOption, PageDto } from "@/lib/workflow/api"

const BASE = `${API_BASE_URL}/api/v1/departments`

export type DepartmentStatus = "active" | "inactive"

/** Mirrors backend/dms-core's DepartmentResponse. */
export interface DepartmentDto {
  departmentId: string
  name: string
  code: string
  parentDepartmentId: string | null
  parentName: string | null
  headUserId: string | null
  headUserName: string | null
  status: DepartmentStatus
  memberCount: number
  createdAt: string
  updatedAt: string | null
  /** Who documents are parked with when their owner leaves and nobody is named (V113); null = fall back to the head. */
  holdingOwnerUserId?: string | null
  holdingOwnerName?: string | null
}

/** Mirrors backend/dms-core's DepartmentMemberResponse - a direct member (the department's approver pool). */
export interface DepartmentMemberDto {
  userId: string
  displayName: string
  email: string
  status: string
}

/** Mirrors backend/dms-core's UserDepartmentResponse. departmentId is null when the user has none. */
export interface UserDepartmentDto {
  userId: string
  departmentId: string | null
  departmentName: string | null
}

export interface DepartmentFilters {
  status?: DepartmentStatus
  search?: string
}

/** `code` is stored upper-case and can't change afterwards. */
export interface CreateDepartmentPayload {
  name: string
  code: string
  parentDepartmentId?: string | null
  headUserId?: string | null
}

/** Full replacement of the editable fields. */
export interface UpdateDepartmentPayload {
  name: string
  parentDepartmentId: string | null
  headUserId: string | null
  status: DepartmentStatus
  /** Omitted = keep the current holding owner; a user id sets it. */
  holdingOwnerUserId?: string | null
  /** true removes the holding owner (omitting the id above leaves it alone). */
  clearHoldingOwner?: boolean
}

function jsonInit(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
}

/** One page, server-side filtered. `page` is 0-indexed here. */
export async function fetchDepartments(
  filters: DepartmentFilters,
  page: number,
  size: number
): Promise<PageDto<DepartmentDto>> {
  const params = new URLSearchParams()
  if (filters.status) params.set("status", filters.status)
  if (filters.search) params.set("search", filters.search)
  params.set("page", String(page))
  params.set("size", String(size))
  return apiFetchJson(`${BASE}?${params.toString()}`, undefined, "Could not load departments.")
}

/** Every department with the given status (or all), paging through in large chunks - for pickers. */
export async function fetchAllDepartments(status?: DepartmentStatus): Promise<DepartmentDto[]> {
  const departments: DepartmentDto[] = []
  let page = 0
  while (true) {
    const response = await fetchDepartments({ status }, page, 200)
    departments.push(...response.content)
    if (response.last) break
    page += 1
  }
  return departments
}

/** Active departments as picker options. */
export async function fetchActiveDepartmentOptions(): Promise<LookupOption[]> {
  const departments = await fetchAllDepartments("active")
  return departments.map((d) => ({ value: d.departmentId, label: d.name, hint: d.code }))
}

export async function fetchDepartment(id: string): Promise<DepartmentDto> {
  return apiFetchJson(`${BASE}/${id}`, undefined, "Could not load this department.")
}

export async function fetchDepartmentMembers(id: string): Promise<DepartmentMemberDto[]> {
  return apiFetchJson(`${BASE}/${id}/members`, undefined, "Could not load this department's members.")
}

export async function createDepartment(payload: CreateDepartmentPayload): Promise<DepartmentDto> {
  return apiFetchJson(BASE, jsonInit("POST", payload), "Could not create this department.")
}

export async function updateDepartment(id: string, payload: UpdateDepartmentPayload): Promise<DepartmentDto> {
  return apiFetchJson(`${BASE}/${id}`, jsonInit("PUT", payload), "Could not save changes.")
}

/** Soft delete. 409 while it has members, child departments, or a workflow stage routing to it. */
export async function deleteDepartment(id: string): Promise<void> {
  await apiFetch(`${BASE}/${id}`, { method: "DELETE" }, "Could not delete this department.")
}

export async function fetchUserDepartment(userId: string): Promise<UserDepartmentDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/users/${userId}/department`, undefined, "Could not load this user's department.")
}

/** `departmentId: null` clears the user's department. */
export async function assignUserDepartment(userId: string, departmentId: string | null): Promise<UserDepartmentDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/${userId}/department`,
    jsonInit("PUT", { departmentId }),
    "Could not save this user's department."
  )
}
