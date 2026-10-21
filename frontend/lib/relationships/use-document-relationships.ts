"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  createRelationship,
  createRelationshipsBulk,
  fetchDocumentRelationships,
  fetchRelationshipCounts,
  removeRelationship,
  updateRelationship,
} from "@/lib/relationships/api"

import type {
  BulkCreateRelationshipsRequestDto,
  CreateRelationshipRequestDto,
  UpdateRelationshipRequestDto,
} from "@/lib/relationships/types"

/** The Related Documents panel of one document. */
export function useDocumentRelationships(documentId: string) {
  return useQuery({
    queryKey: ["relationships", documentId],
    queryFn: () => fetchDocumentRelationships(documentId),
    retry: false,
  })
}

/** Link counts for a few documents (the preview's "Relationships (n)"). */
export function useRelationshipCounts(documentIds: string[]) {
  const key = [...documentIds].sort().join(",")
  return useQuery({
    queryKey: ["relationship-counts", key],
    queryFn: () => fetchRelationshipCounts(documentIds),
    enabled: documentIds.length > 0,
    staleTime: 30_000,
    retry: false,
  })
}

/** Any change to a link invalidates every panel and count - a link shows on both its documents. */
function useInvalidateRelationships() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ["relationships"] })
    void queryClient.invalidateQueries({ queryKey: ["relationship-counts"] })
  }
}

export function useCreateRelationship() {
  const invalidate = useInvalidateRelationships()
  return useMutation({
    mutationFn: (body: CreateRelationshipRequestDto) => createRelationship(body),
    onSuccess: invalidate,
  })
}

export function useCreateRelationshipsBulk() {
  const invalidate = useInvalidateRelationships()
  return useMutation({
    mutationFn: (body: BulkCreateRelationshipsRequestDto) => createRelationshipsBulk(body),
    onSuccess: invalidate,
  })
}

export function useUpdateRelationship() {
  const invalidate = useInvalidateRelationships()
  return useMutation({
    mutationFn: (args: { id: string; body: UpdateRelationshipRequestDto }) => updateRelationship(args.id, args.body),
    onSuccess: invalidate,
  })
}

export function useRemoveRelationship() {
  const invalidate = useInvalidateRelationships()
  return useMutation({
    mutationFn: (id: string) => removeRelationship(id),
    onSuccess: invalidate,
  })
}
