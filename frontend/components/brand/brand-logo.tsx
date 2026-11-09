/* eslint-disable @next/next/no-img-element -- plain <img>: the artwork is already trimmed and shrunk, and both themes must be in the markup so CSS can pick one without a flash */
import { cn } from "@/lib/utils"

/**
 * The product logo, in its light and dark artwork.
 *
 * `tone` says what it sits on:
 *  - "auto" (default): follows the theme - the light artwork on a light surface, the dark artwork once the `dark` class is
 *    on <html>. Both images are in the markup and CSS shows one, so there is no flash on first paint while the theme loads.
 *  - "on-dark": a surface that is dark in both themes (the sidebar, the login brand panel) - always the dark artwork.
 *
 * `mark` is the leaf alone, for places too small for the wordmark (the collapsed sidebar). Sized by the caller through
 * `className` (set a height; the width follows).
 *
 * The files in /public/images are trimmed, shrunk copies of light-logo.png / dark-logo.png (those are the full-size
 * originals) - a login page should not download a megabyte of logo.
 */
export function BrandLogo({
  alt,
  tone = "auto",
  mark = false,
  className,
}: {
  /** The product name - what a screen reader says. */
  alt: string
  tone?: "auto" | "on-dark"
  mark?: boolean
  className?: string
}) {
  const light = mark ? "/images/logo-mark-light.png" : "/images/logo-light.png"
  const dark = mark ? "/images/logo-mark-dark.png" : "/images/logo-dark.png"
  const size = "w-auto max-w-none select-none"

  if (tone === "on-dark") {
    return (
      <img
        src={dark}
        alt={alt}
        draggable={false}
        className={cn(size, className)}
      />
    )
  }
  return (
    <>
      <img
        src={light}
        alt={alt}
        draggable={false}
        className={cn(size, "dark:hidden", className)}
      />
      <img
        src={dark}
        alt=""
        aria-hidden="true"
        draggable={false}
        className={cn(size, "hidden dark:block", className)}
      />
    </>
  )
}
