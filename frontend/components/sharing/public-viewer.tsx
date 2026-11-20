"use client"

import { useState, type FormEvent, type ReactNode } from "react"
import {
  ChevronRight,
  Download,
  File as FileIcon,
  Folder,
  Home,
  Lock,
  ShieldAlert,
} from "lucide-react"

import {
  AuthHeading,
  AuthShell,
  authButtonClass,
  authFieldClass,
  authInputClass,
  authInputIconClass,
} from "@/components/auth/auth-shell"
import { BrandLogo } from "@/components/brand/brand-logo"
import { APP_NAME } from "@/lib/app-config"
import {
  deniedCopy,
  isPreviewableInline,
  type ListingItem,
  type PublicLinkStatus,
} from "@/lib/sharing/api"

/** Plain page for what an outside visitor sees once they are in: a slim brand header over the content. */
export function PublicFrame({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string
  subtitle?: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="flex flex-wrap items-center gap-3 border-b border-border bg-card px-4 py-3 sm:px-6">
        <BrandLogo alt={APP_NAME} mark className="h-8" />
        <div className="min-w-0 flex-1">
          <h1 className="truncate font-heading text-[16px] font-semibold">
            {title}
          </h1>
          {subtitle && (
            <p className="truncate text-[12.5px] text-muted-foreground">
              {subtitle}
            </p>
          )}
        </div>
        {actions}
      </header>
      <main className="mx-auto w-full max-w-[1100px] flex-1 p-4 sm:p-6">
        {children}
      </main>
    </div>
  )
}

/** A calm "you can't open this" screen - expired, revoked, locked and so on are answers, not errors (SEA-NFR-003). */
export function AccessDenied({
  status,
  lockedUntil,
}: {
  status: PublicLinkStatus | "wrong_password"
  lockedUntil?: string | null
}) {
  const copy = deniedCopy(status)
  return (
    <AuthShell>
      <div className="mb-3 flex justify-center lg:justify-start">
        <ShieldAlert size={32} className="text-primary" />
      </div>
      <AuthHeading title={copy.title} subtitle={copy.body} />
      {status === "locked" && lockedUntil && (
        <p className="text-center text-[13px] text-muted-foreground lg:text-left">
          You can try again after{" "}
          {new Date(lockedUntil).toLocaleTimeString(undefined, {
            timeStyle: "short",
          })}
          .
        </p>
      )}
    </AuthShell>
  )
}

/** The "open this link" card: who shared what, plus a password field when the link needs one. */
export function OpenCard({
  heading,
  subtitle,
  passwordRequired,
  busy,
  error,
  onOpen,
}: {
  heading: string
  subtitle: string
  passwordRequired: boolean
  busy: boolean
  error?: string | null
  onOpen: (password?: string) => void
}) {
  const [password, setPassword] = useState("")
  function submit(e: FormEvent) {
    e.preventDefault()
    onOpen(passwordRequired ? password : undefined)
  }
  return (
    <AuthShell>
      <AuthHeading title={heading} subtitle={subtitle} />
      <form onSubmit={submit} className="space-y-3 sm:space-y-5">
        {passwordRequired && (
          <div>
            <label
              htmlFor="share-password"
              className="mb-1 block text-[12.5px] font-semibold text-foreground max-lg:sr-only sm:mb-2 sm:text-[13px]"
            >
              Password
            </label>
            <div className={authFieldClass}>
              <Lock size={16} className={authInputIconClass} />
              <input
                id="share-password"
                type="password"
                autoComplete="off"
                placeholder="Password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={authInputClass}
              />
            </div>
          </div>
        )}
        {error && (
          <p role="alert" className="text-[13px] text-destructive">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={busy || (passwordRequired && !password)}
          className={authButtonClass}
        >
          {busy ? "Opening…" : "Open"}
        </button>
      </form>
    </AuthShell>
  )
}

/** A document shown inline from its content URL, with a clear fallback when the browser can't render it. */
export function DocumentViewer({
  name,
  mimeType,
  src,
}: {
  name: string
  mimeType?: string
  src: string
}) {
  if (!isPreviewableInline(mimeType)) {
    return (
      <p className="rounded-btn border border-border bg-card p-6 text-center text-[13.5px] text-muted-foreground">
        A preview isn&apos;t available for this kind of file.
      </p>
    )
  }
  if (mimeType?.startsWith("image/")) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- a session-less signed URL, not an optimisable static asset
      <img
        src={src}
        alt={name}
        className="mx-auto max-h-[80svh] max-w-full rounded-btn border border-border bg-card"
      />
    )
  }
  return (
    <iframe
      title={name}
      src={src}
      className="h-[80svh] w-full rounded-btn border border-border bg-card"
    />
  )
}

export function DownloadButton({ href }: { href: string }) {
  return (
    <a
      href={href}
      className="inline-flex h-[38px] items-center gap-2 rounded-btn bg-primary px-4 text-[13px] font-semibold text-primary-foreground hover:bg-primary/80"
    >
      <Download size={15} />
      Download
    </a>
  )
}

function formatSize(bytes?: number): string {
  if (bytes == null) return ""
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Breadcrumb + list of folders and documents - shared by the link page and the guest portal. */
export function ItemBrowser<T extends ListingItem>({
  rootLabel,
  breadcrumb,
  items,
  onOpenFolder,
  onOpenRoot,
  onOpenDocument,
  renderExtra,
}: {
  rootLabel: string
  breadcrumb: { id: string; name: string }[]
  items: T[]
  onOpenFolder: (id: string) => void
  onOpenRoot: () => void
  onOpenDocument: (item: T) => void
  renderExtra?: (item: T) => ReactNode
}) {
  return (
    <div className="flex flex-col gap-3">
      <nav
        aria-label="Folder path"
        className="flex flex-wrap items-center gap-1 text-[13px] text-muted-foreground"
      >
        <button
          type="button"
          onClick={onOpenRoot}
          className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
        >
          <Home size={14} />
          {rootLabel}
        </button>
        {breadcrumb.map((crumb, index) => (
          <span key={crumb.id} className="inline-flex items-center gap-1">
            <ChevronRight size={13} />
            {index === breadcrumb.length - 1 ? (
              <span className="font-medium text-foreground">{crumb.name}</span>
            ) : (
              <button
                type="button"
                onClick={() => onOpenFolder(crumb.id)}
                className="text-primary hover:underline"
              >
                {crumb.name}
              </button>
            )}
          </span>
        ))}
      </nav>
      {items.length === 0 ? (
        <p className="rounded-btn border border-border bg-card p-6 text-center text-[13.5px] text-muted-foreground">
          Nothing in here.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-btn border border-border bg-card">
          {items.map((item) => (
            <li key={`${item.type}-${item.id}`}>
              <button
                type="button"
                onClick={() =>
                  item.type === "folder"
                    ? onOpenFolder(item.id)
                    : onOpenDocument(item)
                }
                className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-muted/50"
              >
                {item.type === "folder" ? (
                  <Folder size={18} className="text-primary" />
                ) : (
                  <FileIcon size={18} className="text-muted-foreground" />
                )}
                <span className="min-w-0 flex-1 truncate text-[14px] font-medium">
                  {item.name}
                </span>
                {renderExtra?.(item)}
                <span className="text-[12px] text-muted-foreground">
                  {formatSize(item.sizeBytes)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
