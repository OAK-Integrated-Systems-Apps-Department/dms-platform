"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import {
  Bell,
  ChevronDown,
  Clock,
  FolderOpen,
  HelpCircle,
  Inbox as InboxIcon,
  LogOut,
  User,
} from "lucide-react"
import { toast } from "sonner"

import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { DocumentPreview } from "@/components/documents/document-preview"
import { TopbarSearch } from "@/components/layout/topbar-search"
import { Dropdown, MenuRow } from "@/components/ui/dropdown"
import { IconButton } from "@/components/ui/icon-button"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useAccountIdentity } from "@/lib/auth/use-account-identity"
import { useDashboardScope } from "@/lib/auth/use-dashboard-scope"
import { usePermissions } from "@/lib/auth/use-permissions"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, markIntentionalLogout } from "@/lib/api-fetch"
import { ROUTES } from "@/lib/app-config"
import { useDashboard } from "@/lib/dashboard/use-dashboard"
import { downloadDocumentFile } from "@/lib/documents/api"
import { saveDownloadedFile } from "@/lib/download-file"
import { useMarkRead, useNotifications, useUnreadCount } from "@/lib/notifications/use-notifications"

const rowClass = "flex items-center gap-2.5 px-3.5 py-2"
const footerClass =
  "flex w-full items-center gap-2 border-t border-border px-3.5 py-2.5 text-left text-[12.5px] font-semibold text-primary hover:bg-muted"

/** A document the preview can open - from the search or the recent list. */
type PreviewRow = Parameters<typeof DocumentPreview>[0]["row"]

/**
 * Top bar: sidebar toggle, document search, help, the recent-documents
 * and notification-inbox menus, and the account menu. Only one dropdown is open at a
 * time; a document picked from the search or the recent list opens in the
 * preview right here.
 *
 * The sidebar toggle is the real <SidebarTrigger> now — it reads/writes
 * the sidebar's open state via useSidebar() internally, so this component
 * no longer needs sidebarOpen/onToggleSidebar props at all.
 *
 * Layout note: this is a flex row with a fixed-width sidebar trigger, a
 * shrinkable search box, a spacer, and several fixed-width buttons. Flex
 * items default to `min-width: auto`, meaning they refuse to shrink below
 * their own content's intrinsic width even when the row is out of space —
 * that let this header overflow its container on narrow viewports and,
 * since the Sidebar is `fixed` (see components/ui/sidebar.tsx), made it
 * look like the sidebar was overlapping the header. `min-w-0` on the
 * header and on the search wrapper lets the search box be the thing that
 * shrinks; `shrink-0` on the trigger/buttons/account menu keeps those at
 * their natural size instead of squeezing icons.
 *
 * Deliberately NOT `overflow-hidden` on the header: the pin/notification/
 * account dropdowns (`Dropdown`, in components/ui/dropdown.tsx) are
 * absolutely positioned to extend below the header's own box on purpose.
 * Clipping the header's overflow clips those dropdowns too — that's what
 * was cutting them down to a sliver instead of letting them drop open.
 */
export function AppTopbar() {
  const router = useRouter()
  const queryClient = useQueryClient()
  // Name and initials come from here, not useCurrentUser directly: a platform
  // administrator has no local user row, and the menu used to render "…" for
  // them. See useAccountIdentity.
  const account = useAccountIdentity()
  const { hasPermission } = usePermissions()
  const canSearch = hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  // Gated for the same reason the home page gates it, and it has to be gated
  // HERE too: react-query enables a query if any mounted observer enables it,
  // so a top bar that always asked for tenant-scoped figures would put the
  // platform administrator's guaranteed 401 straight back.
  const { isTenant } = useDashboardScope()
  const dashboard = useDashboard(30, isTenant)
  const recent = dashboard.data?.recent ?? []
  const unreadCount = useUnreadCount().data ?? 0
  const notifications = useNotifications(0, 6)
  const notifRows = notifications.data?.content ?? []
  const markRead = useMarkRead()
  const [preview, setPreview] = useState<PreviewRow | null>(null)

  const [pinOpen, setPinOpen] = useState(false)
  const [notifOpen, setNotifOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)

  const pinRef = useRef<HTMLDivElement>(null)
  const notifRef = useRef<HTMLDivElement>(null)
  const accountRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (pinRef.current && !pinRef.current.contains(event.target as Node)) {
        setPinOpen(false)
      }
      if (
        notifRef.current &&
        !notifRef.current.contains(event.target as Node)
      ) {
        setNotifOpen(false)
      }
      if (
        accountRef.current &&
        !accountRef.current.contains(event.target as Node)
      ) {
        setAccountOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [])

  function openPreview(row: PreviewRow) {
    setPinOpen(false)
    setNotifOpen(false)
    setPreview(row)
  }

  async function download(documentId: string) {
    try {
      saveDownloadedFile(await downloadDocumentFile(documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  async function handleLogout() {
    // Marked before the logout call itself so a background query already in
    // flight (e.g. the notifications poll) that lands its 401 just after the
    // session cookies are cleared doesn't get mistaken by providers.tsx's
    // global handler for an unexpected session expiry - see
    // markIntentionalLogout's own doc comment.
    markIntentionalLogout()
    // Best-effort: always navigate to /login regardless of outcome, same
    // as before - a logout call failing (already logged out, network
    // hiccup, whatever) shouldn't trap the person on the current page.
    try {
      await apiFetch(`${API_BASE_URL}/api/v1/auth/logout`, { method: "POST" })
    } catch {}
    // Same reasoning as login-page-content.tsx's own onSuccess: a client-side navigation never
    // remounts the Providers tree that owns the React Query cache, so without this, whoever signs in
    // next in this same tab would start out seeing this person's cached "me"-scoped data.
    queryClient.clear()
    router.push(ROUTES.LOGIN)
    router.refresh()
  }

  return (
    <header className="sticky top-0 z-20 flex min-w-0 items-center gap-2 border-b border-border bg-card px-4 py-3.5 sm:gap-3.5 sm:px-[26px]">
      <SidebarTrigger className="shrink-0" />

      {canSearch && (
        <TopbarSearch
          className="max-w-[460px] min-w-0 flex-1"
          onOpen={openPreview}
        />
      )}

      <div className="flex-1" />

      {/* A real link, so the page can be prefetched and opened in a new tab. */}
      <Link
        href="/help"
        title="Help"
        aria-label="Help"
        className="relative flex size-[38px] shrink-0 items-center justify-center rounded-btn border border-border bg-card text-muted-foreground transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <HelpCircle size={17} />
      </Link>

      <div ref={pinRef} className="relative shrink-0">
        <IconButton
          title="Recent documents"
          active={pinOpen}
          onClick={() => {
            setPinOpen((v) => !v)
            setNotifOpen(false)
            setAccountOpen(false)
          }}
        >
          <Clock size={17} />
        </IconButton>
        {pinOpen && (
          <Dropdown title="Recent documents" width={320} noPad>
            {dashboard.isPending ? (
              <p className="px-3.5 py-3 text-[12.5px] text-muted-foreground">
                Loading…
              </p>
            ) : recent.length === 0 ? (
              <p className="px-3.5 py-3 text-[12.5px] text-muted-foreground">
                No documents yet.
              </p>
            ) : (
              <div className="py-1.5">
                {recent.slice(0, 5).map((doc) => {
                  const kind = fileKind(doc.fileName)
                  return (
                    <button
                      key={doc.documentId}
                      type="button"
                      onClick={() => openPreview(doc)}
                      className={`${rowClass} w-full text-left hover:bg-muted`}
                    >
                      <span
                        className={`flex size-7 shrink-0 items-center justify-center rounded-btn ${kind.tone}`}
                      >
                        <kind.icon size={14} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block overflow-hidden text-[12.5px] text-ellipsis whitespace-nowrap">
                          {doc.title}
                        </span>
                        <span className="block text-[11px] text-[var(--ink-faint)]">
                          <FriendlyDate iso={doc.createdAt} />
                        </span>
                      </span>
                    </button>
                  )
                })}
              </div>
            )}
            <button
              type="button"
              className={footerClass}
              onClick={() => {
                setPinOpen(false)
                router.push("/documents")
              }}
            >
              <FolderOpen size={14} />
              Open My Documents
            </button>
          </Dropdown>
        )}
      </div>

      <div ref={notifRef} className="relative shrink-0">
        <IconButton
          title={unreadCount > 0 ? `Notifications - ${unreadCount} unread` : "Notifications"}
          active={notifOpen}
          onClick={() => {
            setNotifOpen((v) => !v)
            setPinOpen(false)
            setAccountOpen(false)
          }}
        >
          <Bell size={17} />
          {unreadCount > 0 && (
            <span className="absolute top-[3px] right-[3px] flex min-w-[15px] items-center justify-center rounded-full border-[1.5px] border-card bg-[var(--rust)] px-[3px] text-[9px] font-bold leading-[13px] text-white">
              {unreadCount > 9 ? "9+" : unreadCount}
            </span>
          )}
        </IconButton>
        {notifOpen && (
          <Dropdown title="Notifications" width={340} noPad>
            {notifications.isPending ? (
              <p className="px-3.5 py-3 text-[12.5px] text-muted-foreground">Loading…</p>
            ) : notifRows.length === 0 ? (
              <p className="px-3.5 py-3 text-[12.5px] text-muted-foreground">Nothing yet.</p>
            ) : (
              <div className="py-1.5">
                {notifRows.map((n) => (
                  <button
                    key={n.notificationId}
                    type="button"
                    onClick={() => {
                      if (!n.read) markRead.mutate(n.notificationId)
                      setNotifOpen(false)
                      if (n.linkUrl) router.push(n.linkUrl)
                    }}
                    className={`${rowClass} w-full items-start text-left hover:bg-muted ${n.read ? "" : "bg-[var(--primary-tint)]"}`}
                  >
                    <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                      <Bell size={11} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-[12.5px] font-semibold text-foreground">{n.title}</span>
                      {n.body && (
                        <span className="block overflow-hidden text-[11.5px] text-ellipsis whitespace-nowrap text-muted-foreground">
                          {n.body}
                        </span>
                      )}
                      <span className="mt-px block text-[11px] text-[var(--ink-faint)]">
                        <FriendlyDate iso={n.createdAt} />
                      </span>
                    </span>
                    {!n.read && <span className="mt-1.5 size-[7px] shrink-0 rounded-full bg-[var(--rust)]" />}
                  </button>
                ))}
              </div>
            )}
            <button
              type="button"
              className={footerClass}
              onClick={() => {
                setNotifOpen(false)
                router.push("/inbox")
              }}
            >
              <InboxIcon size={14} />
              Open the inbox
            </button>
            <button
              type="button"
              className={footerClass}
              onClick={() => {
                setNotifOpen(false)
                router.push("/profile/notifications")
              }}
            >
              <User size={14} />
              Notification preferences
            </button>
          </Dropdown>
        )}
      </div>

      <div ref={accountRef} className="relative shrink-0">
        <button
          type="button"
          onClick={() => {
            setAccountOpen((v) => !v)
            setNotifOpen(false)
            setPinOpen(false)
          }}
          className="flex items-center gap-2 rounded-btn border border-border h-[38px] pr-2.5 pl-[5px]"
          style={{
            background: accountOpen ? "var(--primary-tint)" : "var(--card)",
          }}
        >
          <span className="flex size-7 items-center justify-center rounded-full bg-primary font-heading text-[11.5px] font-bold text-primary-foreground">
            {account.initials ?? "…"}
          </span>
          <ChevronDown size={14} className="text-muted-foreground" />
        </button>
        {accountOpen && (
          <Dropdown width={220} noPad>
            <div className="border-b border-border px-3.5 py-3">
              <div className="text-[13.5px] font-semibold">
                {account.displayName ?? "…"}
              </div>
              <div className="text-[11.5px] text-muted-foreground">
                {account.subtitle}
              </div>
            </div>
            <MenuRow
              icon={User}
              label="Profile"
              onClick={() => {
                setAccountOpen(false)
                router.push("/profile")
              }}
            />
            <div className="my-1 border-t border-border" />
            <MenuRow
              icon={LogOut}
              label="Log out"
              danger
              onClick={handleLogout}
            />
          </Dropdown>
        )}
      </div>

      {preview && (
        <DocumentPreview
          key={preview.documentId}
          row={preview}
          onOpenChange={(open) => !open && setPreview(null)}
          onDownload={() => void download(preview.documentId)}
        />
      )}
    </header>
  )
}
