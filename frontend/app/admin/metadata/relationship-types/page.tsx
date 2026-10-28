import { Suspense } from "react"

import { RelationshipTypesContent } from "./relationship-types-content"

export default function RelationshipTypesPage() {
  return (
    <Suspense fallback={null}>
      <RelationshipTypesContent />
    </Suspense>
  )
}
