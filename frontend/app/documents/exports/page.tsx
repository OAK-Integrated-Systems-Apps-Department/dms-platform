import { Suspense } from "react"

import { ExportsContent } from "./exports-content"

export default function ExportsPage() {
  return (
    <Suspense fallback={null}>
      <ExportsContent />
    </Suspense>
  )
}
