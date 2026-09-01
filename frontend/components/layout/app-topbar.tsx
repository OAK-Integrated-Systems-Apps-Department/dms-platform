"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import {
  Bell,
  Bookmark,
  ChevronDown,
  HelpCircle,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Pin,
  Search,
  User,
} from "lucide-react"

import { activityFeed, recentDocuments } from "@/components/dashboard/data"
import { fileIcon } from "@/components/dashboard/file-icon"
import { Dropdown, MenuRow } from "@/components/ui/dropdown"
import { IconButton } from "@/components/ui/icon-button"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { API_BASE_URL } from "@/lib/api-config"

const rowClass = "flex items-center gap-2.5 px-3.5 py-2"

interface AppTopbarProps {
  /** Current sidebar visibility, so the toggle button shows the right icon/label. */
  sidebarOpen: boolean
  onToggleSidebar: () => void
}

/**
 * Top bar: sidebar toggle, search, help, pinned-documents dropdown,
 * notifications dropdown, and the account menu. Only one dropdown is open
 * at a time.
 */
export function AppTopbar({ sidebarOpen, onToggleSidebar }: AppTopbarProps) {
  const router = useRouter()
  const { data: user, initials } = useCurrentUser()

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
      if (notifRef.current && !notifRef.current.contains(event.target as Node)) {
        setNotifOpen(false)
      }
      if (accountRef.current && !accountRef.current.contains(event.target as Node)) {
        setAccountOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [])

  async function handleLogout() {
    // Logging out client-side should still work even if the backend is
    // unreachable (e.g. not running yet in dev) — the person's intent to
    // leave shouldn't be blocked by a network error they can't fix from
    // this button.
    try {
    await fetch(`${API_BASE_URL}/api/v1/auth/logout`, { method: "POST", credentials: "include" })
    } catch {
      // Ignored — see comment above. If the backend IS reachable but
      // rejects the request (4xx/5xx), fetch resolves rather than
      // throwing, so this catch only ever fires on a real connectivity
      // failure.
    }
    router.push("/login")
    router.refresh()
  }

  return (
    <header className="sticky top-0 z-20 flex items-center gap-3.5 border-b border-border bg-card px-[26px] py-3.5">
      <IconButton
        onClick={onToggleSidebar}
        aria-label={sidebarOpen ? "Hide sidebar" : "Show sidebar"}
        title={sidebarOpen ? "Hide sidebar" : "Show sidebar"}
      >
        {sidebarOpen ? <PanelLeftClose size={17} /> : <PanelLeftOpen size={17} />}
      </IconButton>

      <div className="relative max-w-[460px] flex-1">
        <Search
          size={16}
          className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--ink-faint)]"
        />
        <input
          type="text"
          placeholder="Search documents, folders, people…"
          className="h-[38px] w-full rounded-[9px] border border-border bg-muted pr-3 pl-9 text-[13.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)]"
        />
      </div>

      <div className="flex-1" />

      <IconButton title="Help">
        <HelpCircle size={17} />
      </IconButton>

      <div ref={pinRef} className="relative">
        <IconButton
          title="Pinned documents"
          active={pinOpen}
          onClick={() => {
            setPinOpen((v) => !v)
            setNotifOpen(false)
            setAccountOpen(false)
          }}
        >
          <Pin size={17} />
        </IconButton>
        {pinOpen && (
          <Dropdown title="Pinned documents" width={300}>
            {recentDocuments.slice(0, 3).map((doc) => {
              const Icon = fileIcon(doc.type)
              return (
                <div key={doc.id} className={rowClass}>
                  <Icon size={16} className="text-primary" />
                  <span className="overflow-hidden text-[12.5px] text-ellipsis whitespace-nowrap">
                    {doc.name}
                  </span>
                </div>
              )
            })}
          </Dropdown>
        )}
      </div>

      <div ref={notifRef} className="relative">
        <IconButton
          title="Notifications"
          active={notifOpen}
          onClick={() => {
            setNotifOpen((v) => !v)
            setPinOpen(false)
            setAccountOpen(false)
          }}
        >
          <Bell size={17} />
          <span className="absolute top-[7px] right-[7px] size-[7px] rounded-full border-[1.5px] border-card bg-[var(--rust)]" />
        </IconButton>
        {notifOpen && (
          <Dropdown title="Notifications" width={320}>
            {activityFeed.slice(0, 4).map((entry) => {
              const Icon = entry.icon
              return (
                <div key={entry.id} className={rowClass}>
                  <Icon size={15} className="mt-0.5 shrink-0 text-primary" />
                  <div className="min-w-0 flex-1">
                    <div className="text-[12.5px] text-foreground">
                      <b>{entry.who}</b> {entry.action}{" "}
                      <span className="text-muted-foreground">{entry.target}</span>
                    </div>
                    <div className="mt-px text-[11px] text-[var(--ink-faint)]">
                      {entry.time}
                    </div>
                  </div>
                </div>
              )
            })}
          </Dropdown>
        )}
      </div>

      <div ref={accountRef} className="relative">
        <button
          type="button"
          onClick={() => {
            setAccountOpen((v) => !v)
            setNotifOpen(false)
            setPinOpen(false)
          }}
          className="flex items-center gap-2 rounded-[10px] border border-border py-[5px] pr-2.5 pl-[5px]"
          style={{ background: accountOpen ? "var(--primary-tint)" : "var(--card)" }}
        >
          <span className="flex size-7 items-center justify-center rounded-full bg-primary font-heading text-[11.5px] font-bold text-primary-foreground">
            {initials ?? "…"}
          </span>
          <ChevronDown size={14} className="text-muted-foreground" />
        </button>
        {accountOpen && (
          <Dropdown width={220} noPad>
            <div className="border-b border-border px-3.5 py-3">
              <div className="text-[13.5px] font-semibold">{user?.displayName ?? "…"}</div>
              <div className="text-[11.5px] text-muted-foreground">{user?.status ?? ""}</div>
            </div>
            <MenuRow icon={Bookmark} label="Bookmarks" />
            <MenuRow icon={User} label="Profile" />
            <div className="my-1 border-t border-border" />
            <MenuRow icon={LogOut} label="Log out" danger onClick={handleLogout} />
          </Dropdown>
        )}
      </div>
    </header>
  )
}