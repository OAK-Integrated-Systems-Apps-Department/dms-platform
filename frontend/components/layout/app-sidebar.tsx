"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { useTheme } from "next-themes"
import {
  Layers,
  Moon,
  PenTool,
  Settings,
  ShieldCheck,
  Sun,
  X,
} from "lucide-react"

import { cn } from "@/lib/utils"
import type { NavItem } from "@/components/dashboard/types"
import { navSections } from "@/components/dashboard/nav-config"
import { Dropdown } from "@/components/ui/dropdown"
import { Toggle } from "@/components/ui/toggle"

interface AppSidebarProps {
  activeLabel?: string
  onNavigate?: (label: string) => void
}

/**
 * Left navigation rail — always the dark "vault" chrome, in both app
 * themes. Reads its items from `nav-config.ts`. The Settings popover at
 * the bottom controls dark mode (via next-themes) plus two demo toggles.
 */
export function AppSidebar({
  activeLabel = "My Documents",
  onNavigate,
}: AppSidebarProps) {
  const { resolvedTheme, setTheme } = useTheme()
  const isDark = resolvedTheme === "dark"
  const pathname = usePathname()

  const [settingsOpen, setSettingsOpen] = useState(false)
  const [requireSignature, setRequireSignature] = useState(true)
  const [autoLock, setAutoLock] = useState(false)
  const settingsRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (
        settingsRef.current &&
        !settingsRef.current.contains(event.target as Node)
      ) {
        setSettingsOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [])

  return (
    <aside className="sticky top-0 flex h-svh w-[248px] shrink-0 flex-col bg-sidebar px-3.5 py-[22px] text-sidebar-foreground">
      <div className="mb-[30px] flex items-center gap-2.5 px-2">
        <div className="flex size-[34px] items-center justify-center rounded-lg border border-white/15 bg-sidebar-primary">
          <Layers size={18} color="var(--sidebar-primary-foreground)" />
        </div>
        <div>
          <div className="font-heading text-[17px] leading-none font-semibold">
            Ledger
          </div>
          <div className="mt-0.5 text-[10.5px] tracking-[0.08em] text-[var(--sidebar-muted)]">
            DOCUMENT VAULT
          </div>
        </div>
      </div>

      {navSections.map((section, index) => (
        <div key={section.label ?? `section-${index}`}>
          {section.label && (
            <>
              <div className="my-4 h-px bg-white/[0.08]" />
              <div className="px-2.5 pb-1.5 text-[10.5px] tracking-[0.08em] text-[var(--sidebar-muted)]">
                {section.label.toUpperCase()}
              </div>
            </>
          )}
          <nav className="flex flex-col gap-0.5">
            {section.items.map((item: NavItem) => {
              const isActive = item.href
                ? item.href === "/"
                  ? pathname === "/"
                  : pathname.startsWith(item.href)
                : item.label === activeLabel
              const Icon = item.icon
              const itemClassName = cn(
                "flex items-center gap-2.5 rounded-lg px-2.5 py-[9px] text-left text-[13.5px] transition-colors",
                isActive
                  ? "bg-sidebar-primary font-semibold text-sidebar-primary-foreground"
                  : "font-medium text-sidebar-foreground hover:bg-sidebar-accent"
              )
              const content = (
                <>
                  <Icon
                    size={16.5}
                    className={cn("shrink-0", !isActive && "opacity-85")}
                  />
                  <span className="flex-1">{item.label}</span>
                  {item.badge != null && (
                    <span className="rounded-full bg-[var(--gold)] px-1.5 py-px text-[10.5px] font-bold text-[#221a0c]">
                      {item.badge}
                    </span>
                  )}
                </>
              )

              if (item.href) {
                return (
                  <Link
                    key={item.label}
                    href={item.href}
                    className={itemClassName}
                  >
                    {content}
                  </Link>
                )
              }

              return (
                <button
                  key={item.label}
                  type="button"
                  onClick={() => onNavigate?.(item.label)}
                  className={itemClassName}
                >
                  {content}
                </button>
              )
            })}
          </nav>
        </div>
      ))}

      <div className="flex-1" />

      <div ref={settingsRef} className="relative">
        {settingsOpen && (
          <Dropdown
            width={260}
            noPad
            className="top-auto right-auto bottom-[52px] left-0 text-foreground"
          >
            <div className="flex items-center justify-between p-3.5 pb-2.5">
              <span className="text-[13.5px] font-semibold">Settings</span>
              <button
                type="button"
                onClick={() => setSettingsOpen(false)}
                className="text-[var(--ink-faint)]"
              >
                <X size={15} />
              </button>
            </div>

            <div className="flex items-center justify-between px-3.5 py-2">
              <div className="flex items-center gap-2">
                {isDark ? (
                  <Moon size={15} className="text-muted-foreground" />
                ) : (
                  <Sun size={15} className="text-muted-foreground" />
                )}
                <span className="text-[13px]">Dark mode</span>
              </div>
              <Toggle
                checked={isDark}
                onChange={(checked) => setTheme(checked ? "dark" : "light")}
                label="Toggle dark mode"
              />
            </div>

            <div className="flex items-center justify-between px-3.5 py-2">
              <div className="flex items-center gap-2">
                <PenTool size={15} className="text-muted-foreground" />
                <span className="text-[13px]">
                  Require e-signature on approval
                </span>
              </div>
              <Toggle
                checked={requireSignature}
                onChange={setRequireSignature}
                label="Require e-signature on approval"
              />
            </div>

            <div className="flex items-center justify-between px-3.5 py-2">
              <div className="flex items-center gap-2">
                <ShieldCheck size={15} className="text-muted-foreground" />
                <span className="text-[13px]">Auto-lock signed documents</span>
              </div>
              <Toggle
                checked={autoLock}
                onChange={setAutoLock}
                label="Auto-lock signed documents"
              />
            </div>

            <button
              type="button"
              className="mt-2.5 w-full rounded-lg border border-border bg-muted px-2.5 py-2 text-left text-[12.5px]"
            >
              Manage signature certificates →
            </button>
          </Dropdown>
        )}

        <button
          type="button"
          onClick={() => setSettingsOpen((value) => !value)}
          className={cn(
            "flex w-full items-center gap-2.5 rounded-lg border border-white/[0.08] px-2.5 py-[9px] text-[13.5px]",
            settingsOpen ? "bg-sidebar-primary" : "bg-transparent"
          )}
        >
          <Settings size={16.5} />
          Settings
        </button>
      </div>
    </aside>
  )
}
