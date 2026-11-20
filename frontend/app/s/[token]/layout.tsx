import type { Metadata } from "next"

// The page itself is a client component, so its title lives here. A share link must never be indexed.
export const metadata: Metadata = {
  title: "Shared with you",
  robots: { index: false, follow: false },
}

export default function Layout({ children }: { children: React.ReactNode }) {
  return children
}
