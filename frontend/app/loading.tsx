import { LoaderArt } from "@/components/ui/loading-state"

/**
 * Shown by Next while a page is being fetched on navigation, before the page's own layout (sidebar, top bar) exists: the
 * same animated stack of sheets as the in-page loading state, centred on the app background with the product name.
 */
export default function Loading() {
  return (
    <div
      role="status"
      aria-live="polite"
      className="dms-loader flex min-h-svh flex-col items-center justify-center gap-6 bg-background px-6 text-center"
    >
      <LoaderArt />
      <div className="flex flex-col items-center gap-2">
        <p className="font-heading text-[20px] font-semibold tracking-[-0.01em] text-foreground">OAK iDocs</p>
        <p className="text-[13px] text-muted-foreground">Getting your documents ready…</p>
        <span className="dms-loader-dots mt-1" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </div>
    </div>
  )
}
