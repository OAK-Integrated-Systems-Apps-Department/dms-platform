import { Inter, IBM_Plex_Mono, Dancing_Script } from "next/font/google"
import { cookies } from "next/headers"

import "./globals.css"
import { Providers } from "@/components/providers/providers"
import { SIDEBAR_COOKIE_NAME } from "@/components/ui/sidebar"
import { cn } from "@/lib/utils"

const inter = Inter({ subsets: ["latin"], variable: "--font-sans" })

const headingFont = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["500", "600"],
  variable: "--font-heading",
})

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-mono",
})

/** A typed e-signature's display font (1.4) - see `SignAndApproveDialog`/`SignatureRow`. */
const signatureFont = Dancing_Script({
  subsets: ["latin"],
  weight: ["600"],
  variable: "--font-signature",
})

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  const cookieStore = await cookies()
  const sidebarOpen = cookieStore.get(SIDEBAR_COOKIE_NAME)?.value !== "false" // defaults to expanded

  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={cn(
        "antialiased",
        plexMono.variable,
        "font-sans",
        inter.variable,
        headingFont.variable,
        signatureFont.variable
      )}
    >
      <body>
        <Providers initialSidebarOpen={sidebarOpen}>{children}</Providers>
      </body>
    </html>
  )
}