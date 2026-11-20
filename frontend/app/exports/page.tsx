import { redirect } from "next/navigation"

/** Exports were replaced by downloads in Documents; old links and bookmarks still work. */
export default function LegacyExportsPage() {
  redirect("/documents")
}
