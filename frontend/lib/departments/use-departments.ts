"use client"

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"

import {
  assignUserDepartment,
  deleteDepartment,
  fetchAllDepartments,
  fetchDepartment,
  fetchDepartmentMembers,
  fetchDepartments,
  fetchUserDepartment,
  updateDepartment,
} from "./api"
import type { DepartmentFilters, UpdateDepartmentPayload } from "./api"

/** Server-driven department list. `page` is 1-indexed here, converted to the backend's 0-indexed page. */
export function useDepartments(filters: DepartmentFilters, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ["departments", "list", filters, page, size],
    queryFn: () => fetchDepartments(filters, page - 1, size),
    placeholderData: keepPreviousData,
    enabled,
  })
}

/** Every department regardless of status - for the parent picker on the department form. */
export function useAllDepartments(enabled = true) {
  return useQuery({
    queryKey: ["departments", "all"],
    queryFn: () => fetchAllDepartments(),
    enabled,
    staleTime: 60_000,
  })
}

/** One department, its direct members, and its update/delete actions. */
export function useDepartment(id: string) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ["departments", "detail", id],
    queryFn: () => fetchDepartment(id),
    enabled: Boolean(id),
    retry: false,
  })

  const membersQuery = useQuery({
    queryKey: ["departments", "detail", id, "members"],
    queryFn: () => fetchDepartmentMembers(id),
    enabled: Boolean(id),
    retry: false,
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["departments"] })
    queryClient.invalidateQueries({ queryKey: ["lookup", "departments"] })
  }

  const updateMutation = useMutation({
    mutationFn: (payload: UpdateDepartmentPayload) => updateDepartment(id, payload),
    onSuccess: () => {
      invalidate()
      toast.success("Department saved.")
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => deleteDepartment(id),
    // Lists only: refetching the deleted department itself (still mounted
    // until the page navigates away) would 404.
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["departments", "list"] })
      queryClient.invalidateQueries({ queryKey: ["departments", "all"] })
      queryClient.invalidateQueries({ queryKey: ["lookup", "departments"] })
      toast.success("Department deleted.")
    },
    onError: (error) => toast.error(workflowErrorMessage(error, "Could not delete this department.")),
  })

  return {
    ...query,
    department: query.data,
    members: membersQuery.data ?? [],
    membersLoading: membersQuery.isPending,
    /** Resolves to an error message, or null on success - the form shows it inline. */
    update: async (payload: UpdateDepartmentPayload): Promise<string | null> => {
      try {
        await updateMutation.mutateAsync(payload)
        return null
      } catch (err) {
        return workflowErrorMessage(err, "Could not save changes.")
      }
    },
    remove: async (): Promise<boolean> => {
      try {
        await deleteMutation.mutateAsync()
        return true
      } catch {
        return false
      }
    },
    isDeleting: deleteMutation.isPending,
  }
}

/** A user's primary department (the Edit User screen's Department select). */
export function useUserDepartment(userId: string, enabled = true) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ["user-department", userId],
    queryFn: () => fetchUserDepartment(userId),
    enabled: Boolean(userId) && enabled,
    retry: false,
  })

  const assignMutation = useMutation({
    mutationFn: (departmentId: string | null) => assignUserDepartment(userId, departmentId),
    onSuccess: (result) => {
      queryClient.setQueryData(["user-department", userId], result)
      queryClient.invalidateQueries({ queryKey: ["departments"] })
      toast.success(result.departmentName ? `Department set to ${result.departmentName}.` : "Department cleared.")
    },
    onError: (error) => toast.error(workflowErrorMessage(error, "Could not save this user's department.")),
  })

  return {
    ...query,
    assign: assignMutation.mutate,
    isAssigning: assignMutation.isPending,
  }
}
