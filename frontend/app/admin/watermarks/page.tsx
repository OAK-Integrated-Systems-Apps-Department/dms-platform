import { Suspense } from "react"

import { WatermarkTemplatesContent } from "./watermark-templates-content"

export default function WatermarkTemplatesPage() {
  return (
    <Suspense fallback={null}>
      <WatermarkTemplatesContent />
    </Suspense>
  )
}
