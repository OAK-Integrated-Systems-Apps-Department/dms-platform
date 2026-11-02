import { Suspense } from "react"

import { OwnershipContent } from "@/components/ownership/ownership-content"

export default function OwnershipPage() {
  return (
    <Suspense fallback={null}>
      <OwnershipContent />
    </Suspense>
  )
}
