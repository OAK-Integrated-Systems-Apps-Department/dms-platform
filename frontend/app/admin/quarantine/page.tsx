import { Suspense } from "react"

import { QuarantineContent } from "./quarantine-content"

export default function QuarantinePage() {
  return (
    <Suspense fallback={null}>
      <QuarantineContent />
    </Suspense>
  )
}
