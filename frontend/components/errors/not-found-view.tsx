"use client"

import type { CSSProperties } from "react"
import Link from "next/link"
import { LayoutDashboard } from "lucide-react"

import "@/components/errors/not-found.css"
import { APP_NAME } from "@/lib/app-config"

const delay = (seconds: number) =>
  ({ "--nf-delay": `${seconds}s` }) as CSSProperties

/**
 * The app's 404 - shown for any address that doesn't exist. A giant 4·0·4
 * whose 0 is a document sheet stamped NOT FOUND, a short explanation, and
 * the way back to the dashboard. It stands on its own (no sidebar), so it works
 * whether or not the person is signed in.
 */
export function NotFoundView() {
  return (
    <main className="nf-page relative isolate flex min-h-screen flex-col overflow-hidden bg-background">
      <title>{`Page not found · ${APP_NAME}`}</title>
      <div
        aria-hidden="true"
        className="nf-ruled pointer-events-none absolute inset-0 -z-10"
      />
      <span
        aria-hidden="true"
        className="nf-orb -top-32 -left-24 -z-10 size-[420px]"
        style={{ background: "var(--nf-glow)" }}
      />
      <span
        aria-hidden="true"
        className="nf-orb -right-24 -bottom-40 -z-10 size-[460px]"
        style={{ background: "var(--nf-gold-glow)", animationDelay: "-8s" }}
      />

      <div className="flex flex-1 flex-col items-center justify-center px-6 py-12 text-center">
        {/* 4 · (a stamped sheet) · 4 */}
        <div
          aria-hidden="true"
          className="mb-10 flex items-center justify-center gap-3 select-none sm:gap-6"
        >
          <span className="nf-digit text-[clamp(96px,22vw,200px)]">4</span>

          <div className="nf-scene relative h-[clamp(124px,27vw,240px)] w-[clamp(96px,21vw,186px)]">
            {/* Where the file should be. */}
            <div className="nf-slot absolute inset-0 translate-x-3 translate-y-3 rounded-2xl border-2 border-dashed border-[var(--border-strong)]" />
            <div className="nf-sheet absolute inset-0 overflow-hidden rounded-2xl border border-border bg-card">
              <div className="h-[6%] bg-primary" />
              <div className="flex flex-col gap-[7%] px-[12%] pt-[14%]">
                <span className="h-[clamp(6px,1.3vw,11px)] w-1/2 rounded-full bg-primary/25" />
                {[90, 75, 84, 60, 78, 52].map((width, i) => (
                  <span
                    key={i}
                    className="h-[clamp(3px,0.7vw,6px)] rounded-full bg-muted-foreground/15"
                    style={{ width: `${width}%` }}
                  />
                ))}
              </div>
              {/* The stamp. */}
              <div className="nf-stamp absolute inset-x-[6%] top-[42%] rounded-md border-[3px] border-[var(--rust)] p-[3px]">
                <div className="nf-stamp-inner rounded-[3px] border border-[var(--rust)] px-1 py-[clamp(3px,0.8vw,6px)] font-heading text-[clamp(8px,2.2vw,20px)] font-semibold tracking-[0.06em] whitespace-nowrap text-[var(--rust)] sm:tracking-[0.12em]">
                  NOT FOUND
                </div>
              </div>
            </div>
          </div>

          <span className="nf-digit text-[clamp(96px,22vw,200px)]">4</span>
        </div>

        <p
          className="nf-fade mb-2 font-mono text-[11px] font-medium tracking-[0.2em] text-primary"
          style={delay(0.35)}
        >
          ERROR 404
        </p>
        <h1
          className="nf-fade font-heading text-[26px] leading-tight font-semibold tracking-[-0.01em] sm:text-[32px]"
          style={delay(0.45)}
        >
          Page not found
        </h1>
        <p
          className="nf-fade mt-3 max-w-[460px] text-[14px] leading-relaxed text-muted-foreground"
          style={delay(0.55)}
        >
          The page you&apos;re looking for doesn&apos;t exist or may have been
          moved. Check the address, or return to your dashboard.
        </p>

        <Link
          href="/"
          className="nf-fade mt-8 inline-flex items-center justify-center gap-2 rounded-btn border border-primary bg-primary px-5 py-2.5 text-[13.5px] font-semibold text-primary-foreground shadow-[0_3px_0_var(--nf-depth),0_10px_20px_-10px_var(--nf-glow)] transition-[transform,box-shadow] duration-150 hover:-translate-y-px focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none active:translate-y-px"
          style={delay(0.65)}
        >
          <LayoutDashboard size={16} aria-hidden="true" />
          Back to dashboard
        </Link>

        <p
          className="nf-fade mt-8 max-w-[440px] text-[12px] text-[var(--ink-faint)]"
          style={delay(0.75)}
        >
          If you think this is a mistake, contact your system administrator.
        </p>
      </div>
    </main>
  )
}
