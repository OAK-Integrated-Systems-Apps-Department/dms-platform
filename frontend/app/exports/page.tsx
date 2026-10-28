import { redirect } from "next/navigation"

/** Exports were replaced by downloads in My Documents; old links and bookmarks still work. */
export default function LegacyExportsPage() {
  redirect("/documents")
}
