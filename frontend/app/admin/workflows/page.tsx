import { Suspense } from "react"

import { WorkflowsContent } from "./workflows-content"

export default function WorkflowTemplatesPage() {
  return (
    <Suspense fallback={null}>
      <WorkflowsContent />
    </Suspense>
  )
}
