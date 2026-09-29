import { Suspense } from "react"

import { AuditLogContent } from "./audit-log-content"

export default function AuditLogPage() {
  return (
    <Suspense fallback={null}>
      <AuditLogContent />
    </Suspense>
  )
}
