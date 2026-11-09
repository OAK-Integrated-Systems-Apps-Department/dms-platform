import type { ReactNode } from "react"
import { BrandLogo } from "@/components/brand/brand-logo"

import { LoginHeadline, LoginSpheres } from "@/components/auth/login-hero"
import { APP_NAME } from "@/lib/app-config"

/**
 * The signed-out screens' shared frame: sign in, forgot password and choose a
 * new password all use it, so they look like one product.
 *
 * From `lg` up it is one rounded card, split: the brand panel on the left, the
 * form on the right. Below that it is the phone layout: the oak-leaf photo
 * across the top, a wave of white rising over it, and the form on the white
 * below. The form content is whatever the screen passes as `children`.
 */
export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-svh items-center justify-center bg-card lg:bg-background lg:bg-[radial-gradient(circle_at_15%_10%,var(--primary-tint),transparent_55%)] lg:p-8 [@media(max-height:760px)]:lg:p-4">
      {/* One rounded card, split: brand panel | form */}
      <div className="grid min-h-svh w-full grid-rows-[auto_1fr] lg:h-[min(660px,calc(100svh-2rem))] lg:min-h-0 lg:min-h-[500px] lg:max-w-[1120px] lg:grid-cols-2 lg:grid-rows-none lg:overflow-hidden lg:rounded-[28px] lg:bg-card lg:shadow-[0_30px_80px_color-mix(in_srgb,var(--primary)_22%,transparent)] lg:ring-1 lg:ring-border">
        {/* Brand panel: wide screens only */}
        <aside
          aria-hidden="true"
          className="relative hidden flex-col overflow-hidden bg-[linear-gradient(150deg,#2a6a50_0%,#17402e_100%)] p-10 text-white lg:flex xl:p-12 [@media(max-height:760px)]:lg:p-7"
        >
          {/* Oak-leaf photo, tinted into the brand green and drifting slowly */}
          <div
            className="pointer-events-none absolute inset-0 bg-cover bg-center opacity-[0.14] mix-blend-luminosity"
            style={{ backgroundImage: "url('/images/oak-leaves.jpg')" }}
          />
          {/* Bobbing spheres */}
          <LoginSpheres />
          {/* Aurora glow */}
          <div className="pointer-events-none absolute top-1/3 -left-24 size-[420px] rounded-full bg-[radial-gradient(closest-side,rgb(185_138_62/0.25),transparent)]" />

          <div className="relative flex items-center gap-3">
            <div>
              {/* The brand panel is dark green in both themes - the dark artwork. */}
              <BrandLogo tone="on-dark" alt={APP_NAME} className="h-14" />
            </div>
          </div>

          <div className="relative my-auto py-8">
            <LoginHeadline appName={APP_NAME} />
          </div>
        </aside>

        {/* Phone layout: the oak-leaf photo, with a wave of white rising over its lower edge */}
        <header
          aria-hidden="true"
          className="relative h-[36svh] max-h-[320px] min-h-[210px] overflow-hidden bg-[linear-gradient(150deg,#2a6a50_0%,#17402e_100%)] lg:hidden"
        >
          {/* Same treatment as the web brand panel: the photo tinted into the brand green */}
          <div
            className="pointer-events-none absolute inset-0 bg-cover bg-center opacity-[0.3] mix-blend-luminosity"
            style={{ backgroundImage: "url('/images/oak-leaves.jpg')" }}
          />
          <div
            className="lg-bob pointer-events-none absolute -top-16 -right-14 size-52 rounded-full opacity-70 shadow-[inset_-10px_-14px_36px_rgb(0_0_0/0.28)]"
            style={{
              background:
                "radial-gradient(circle at 30% 24%, rgb(255 255 255 / 0.3), rgb(255 255 255 / 0) 42%), linear-gradient(150deg, #2f7557 0%, #1f4d3a 52%, #123527 100%)",
              animationDuration: "24s",
            }}
          />
          <div className="pointer-events-none absolute top-1/3 -left-20 size-72 rounded-full bg-[radial-gradient(closest-side,rgb(185_138_62/0.25),transparent)]" />
          <div className="relative flex items-center justify-center gap-2.5 pt-5 text-white">
            <div className="text-left">
              <BrandLogo tone="on-dark" alt={APP_NAME} className="h-10" />
            </div>
          </div>
          <svg
            viewBox="0 0 400 80"
            preserveAspectRatio="none"
            className="absolute inset-x-0 -bottom-px h-14 w-full fill-card"
          >
            <path d="M0 46 C110 6 230 78 400 22 L400 80 L0 80 Z" />
          </svg>
        </header>

        {/* Form panel */}
        <main className="relative flex items-start justify-center overflow-hidden bg-card px-4 pt-5 pb-8 sm:px-10 sm:pb-10 lg:items-center lg:px-12 lg:py-10 xl:px-16">
          {/* Corner sphere, echoing the brand panel */}
          <div
            aria-hidden="true"
            className="lg-bob pointer-events-none absolute -right-20 -bottom-24 hidden size-[220px] rounded-full opacity-90 shadow-[inset_-10px_-14px_36px_rgb(0_0_0/0.3)] lg:block"
            style={{
              background:
                "radial-gradient(circle at 30% 24%, rgb(255 255 255 / 0.3), rgb(255 255 255 / 0) 42%), linear-gradient(150deg, #2f7557 0%, #1f4d3a 52%, #123527 100%)",
              animationDuration: "20s",
            }}
          />

          <div className="relative w-full max-w-[400px] animate-[lg-rise_0.7s_cubic-bezier(0.2,0.7,0.2,1)_both] motion-reduce:animate-none">
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}

/**
 * Soft green-tinted fill at every size (the phone design). Below `lg` the field
 * is borderless; from `lg` up it keeps its light border. Size and corner radius
 * are the same everywhere. Leaves room on the left for a 16px icon.
 */
export const authInputClass =
  "h-full w-full appearance-none rounded-btn border border-border max-lg:border-transparent bg-[var(--primary-tint)] py-3 pr-3.5 pl-10 text-[16px] text-foreground sm:py-3.5 sm:pl-11 sm:text-sm transition-colors outline-none placeholder:text-[var(--ink-faint)] focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"

/**
 * Wrapper around an `authInputClass` field and its icons. The height is pinned
 * (not left to the input) so iOS Safari can never stretch it and push the
 * absolutely-placed icons away from the field.
 */
export const authFieldClass = "relative h-[50px] w-full"

/** The icon inside the left edge of an `authInputClass` field. */
export const authInputIconClass =
  "pointer-events-none absolute inset-y-0 left-3.5 my-auto h-4 w-4 text-foreground max-lg:text-primary sm:left-4"

/** The show/hide-password button on the right edge of an `authInputClass` field. */
export const authInputToggleClass =
  "absolute inset-y-0 right-0 flex w-12 touch-manipulation items-center justify-center text-primary lg:w-auto lg:pr-4 lg:pl-3 transition-colors hover:text-[var(--primary-hover)]"

export const authButtonClass =
  "group flex w-full items-center justify-center gap-2 rounded-btn max-lg:normal-case max-lg:tracking-normal border border-[var(--primary-hover)] bg-primary py-3.5 text-sm font-bold tracking-[0.08em] text-primary-foreground uppercase shadow-[0_8px_22px_color-mix(in_srgb,var(--primary)_32%,transparent)] transition-all hover:-translate-y-px hover:bg-[var(--primary-hover)] hover:shadow-[0_12px_28px_color-mix(in_srgb,var(--primary)_40%,transparent)] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-70 disabled:shadow-none"

/**
 * A screen's title and one line under it: centred and green on a phone, left
 * and dark from `lg` up, like the sign-in page.
 */
export function AuthHeading({
  title,
  subtitle,
}: {
  title: ReactNode
  subtitle?: ReactNode
}) {
  return (
    <>
      <h1 className="text-center font-heading text-[28px] leading-[1.1] font-extrabold tracking-tight text-foreground max-lg:text-primary sm:text-[40px] lg:text-left">
        {title}
      </h1>
      {subtitle ? (
        <p className="mt-2 mb-6 text-center text-[13px] text-muted-foreground sm:mt-3 sm:mb-8 sm:text-sm lg:text-left">
          {subtitle}
        </p>
      ) : (
        <div className="mb-6 sm:mb-8" />
      )}
    </>
  )
}
