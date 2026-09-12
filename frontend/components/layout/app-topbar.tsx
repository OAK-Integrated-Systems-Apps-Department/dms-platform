"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import {
  Bell,
  Bookmark,
  ChevronDown,
  HelpCircle,
  LogOut,
  Pin,
  User,
} from "lucide-react"

import { activityFeed, recentDocuments } from "@/components/dashboard/data"
import { fileIcon } from "@/components/dashboard/file-icon"
import { Dropdown, MenuRow } from "@/components/ui/dropdown"
import { IconButton } from "@/components/ui/icon-button"
import { SearchInput } from "@/components/ui/search-input"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { useCurrentUser } from "@/lib/auth/use-current-user"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch } from "@/lib/api-fetch"

const rowClass = "flex items-center gap-2.5 px-3.5 py-2"

/**
 * Top bar: sidebar toggle, search, help, pinned-documents dropdown,
 * notifications dropdown, and the account menu. Only one dropdown is open
 * at a time.
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
  const { data: user, initials } = useCurrentUser()

  const [pinOpen, setPinOpen] = useState(false)
  const [notifOpen, setNotifOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState("")

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

  async function handleLogout() {
    // Best-effort: always navigate to /login regardless of outcome, same
    // as before - a logout call failing (already logged out, network
    // hiccup, whatever) shouldn't trap the person on the current page.
    try {
      await apiFetch(`${API_BASE_URL}/api/v1/auth/logout`, { method: "POST" })
    } catch {}
    router.push("/login")
    router.refresh()
  }

  return (
    <header className="sticky top-0 z-20 flex min-w-0 items-center gap-3.5 border-b border-border bg-card px-[26px] py-3.5">
      <SidebarTrigger className="shrink-0" />

      <SearchInput
        className="min-w-0 max-w-[460px] flex-1"
        value={searchQuery}
        onChange={setSearchQuery}
          placeholder="Search documents, folders, people…"
        />

      <div className="flex-1" />

      <IconButton title="Help" className="shrink-0">
        <HelpCircle size={17} />
      </IconButton>

      <div ref={pinRef} className="relative shrink-0">
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

      <div ref={notifRef} className="relative shrink-0">
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
                      <span className="text-muted-foreground">
                        {entry.target}
                      </span>
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

      <div ref={accountRef} className="relative shrink-0">
        <button
          type="button"
          onClick={() => {
            setAccountOpen((v) => !v)
            setNotifOpen(false)
            setPinOpen(false)
          }}
          className="flex items-center gap-2 rounded-md border border-border py-[5px] pr-2.5 pl-[5px]"
          style={{
            background: accountOpen ? "var(--primary-tint)" : "var(--card)",
          }}
        >
          <span className="flex size-7 items-center justify-center rounded-full bg-primary font-heading text-[11.5px] font-bold text-primary-foreground">
            {initials ?? "…"}
          </span>
          <ChevronDown size={14} className="text-muted-foreground" />
        </button>
        {accountOpen && (
          <Dropdown width={220} noPad>
            <div className="border-b border-border px-3.5 py-3">
              <div className="text-[13.5px] font-semibold">
                {user?.displayName ?? "…"}
              </div>
              <div className="text-[11.5px] text-muted-foreground">
                {user?.status ?? ""}
              </div>
            </div>
            <MenuRow icon={Bookmark} label="Bookmarks" />
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
    </header>
  )
}