import { redirect } from "next/navigation"

/** My Exports moved into the Documents area; old links and bookmarks still work. */
export default function LegacyExportsPage() {
  redirect("/documents/exports")
}
