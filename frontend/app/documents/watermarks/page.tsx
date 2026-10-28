import { Suspense } from "react"

import { WatermarksContent } from "./watermarks-content"

export default function MyWatermarkedCopiesPage() {
  return (
    <Suspense fallback={null}>
      <WatermarksContent />
    </Suspense>
  )
}
