/**
 * Shows a (possibly transparent-background) signature image on a checkerboard,
 * the standard way to make a transparent PNG's transparency visible at all -
 * without it, a successfully-background-removed signature looks identical to
 * a broken/blank image against this app's own light card background.
 */
export function TransparentImagePreview({ imageBase64, alt }: { imageBase64: string; alt: string }) {
  return (
    <div
      className="mt-2 flex h-20 items-center justify-center rounded-btn border border-[var(--border-strong)]"
      style={{
        backgroundImage:
          "linear-gradient(45deg, #ccc 25%, transparent 25%), linear-gradient(-45deg, #ccc 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #ccc 75%), linear-gradient(-45deg, transparent 75%, #ccc 75%)",
        backgroundSize: "16px 16px",
        backgroundPosition: "0 0, 0 8px, 8px -8px, -8px 0px",
      }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element -- a small inline base64 PNG, not worth next/image's overhead */}
      <img src={`data:image/png;base64,${imageBase64}`} alt={alt} className="max-h-full max-w-full" />
    </div>
  )
}
