"use client"

import { Suspense } from "react"

import { RolePermissionsContent } from "./role-permissions-content"

export default function RolePermissionsPage() {
  return (
    <Suspense fallback={null}>
      <RolePermissionsContent />
    </Suspense>
  )
}
