// app/login/page.tsx
import type { Metadata } from "next"
import { Suspense } from "react"

import { LoginPageContent } from "./login-page-content"

export const metadata: Metadata = { title: "Sign in" }

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginPageContent />
    </Suspense>
  )
}
