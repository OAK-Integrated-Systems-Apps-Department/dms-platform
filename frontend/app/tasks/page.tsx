import { Suspense } from "react"

import { TasksContent } from "./tasks-content"

export default function MyTasksPage() {
  return (
    <Suspense fallback={null}>
      <TasksContent />
    </Suspense>
  )
}
