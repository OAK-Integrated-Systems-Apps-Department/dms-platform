/**
 * Turns an uploaded scanned/photographed signature (ink on a light
 * background) into a transparent-background PNG, entirely client-side - no
 * image-processing library, no server round trip. Used by the "Upload"
 * option alongside Type/Draw in `SignAndApproveDialog`/`VisualSignatureCard`;
 * the result is stored exactly like a drawn signature (same `kind: "drawn"`,
 * same `image_png` column on the backend) since it ends up being the same
 * kind of thing - a small transparent-background image - however it was
 * produced.
 *
 * The technique: per pixel, alpha = 255 - luminance. A pure white background
 * pixel (luminance 255) becomes fully transparent; dark ink (low luminance)
 * stays fully opaque, keeping its original color (so colored ink isn't
 * forced to black). This is a simple heuristic, not real background
 * removal - it assumes dark ink on a light, fairly uniform background (the
 * common case for a scanned/photographed signature), and a shadow or an
 * off-white scan background will show up as partial transparency rather
 * than vanishing cleanly.
 *
 * Because the heuristic depends on dark ink against a light background, it's
 * also used the other way round as a sanity check: once alpha is computed,
 * the share of "ink" pixels is measured against the whole image, using two
 * different thresholds for the two failure directions - too few pixels with
 * even a faint tint (`PRESENCE_ALPHA_THRESHOLD`) means nothing resembling a
 * signature was found (a blank page, or a washed-out scan); too many pixels
 * with strong, clearly-opaque ink (`INK_ALPHA_THRESHOLD`) means the source
 * wasn't a light, mostly-empty background to begin with (a dark or busy
 * photo). The lower presence threshold matters for light pencil/fine-pen
 * signatures: on the stricter `INK_ALPHA_THRESHOLD` alone, a light signature
 * can read as "nothing found" even though it's perfectly legible - the gap
 * between the two thresholds is deliberate so that case isn't mistaken for a
 * blank page without also making the "too much ink" check equally lenient
 * (which would let a dark/busy photo through).
 *
 * Deliberately NOT doing manual EXIF-orientation correction: a phone photo
 * taken in portrait is commonly stored "sideways" at the pixel level with a
 * rotation tag telling viewers how to display it upright, which sounds like
 * something `<canvas>` would need correcting for - but current Chrome,
 * Firefox and Safari all default `image-orientation` to `from-image`, so
 * `Image`/`<canvas>` already decode pixels pre-rotated per that tag; `img.
 * naturalWidth/naturalHeight` here already reflect the upright orientation.
 * A from-scratch EXIF parser applied on top of that would rotate an
 * already-correct image a second time, producing a mangled result instead
 * of fixing anything.
 */

const MAX_WIDTH = 420
const MAX_HEIGHT = 160
/** Rejected before ever touching the canvas - a multi-megapixel photo would be slow to process for no benefit at this output size. */
const MAX_SOURCE_BYTES = 8_000_000
/** A pixel counts as "ink" once the background-removal alpha crosses this - mirrors what will actually show up solidly opaque in the result. Used for the "too much ink" (busy/dark photo) check. */
const INK_ALPHA_THRESHOLD = 60
/** A pixel counts as having *any* tint once alpha crosses this far lower bar - used only for the "nothing found" check, so a light pencil/fine-pen signature isn't mistaken for a blank page. */
const PRESENCE_ALPHA_THRESHOLD = 18
/** Below this share of tinted pixels, nothing signature-shaped was found - likely a blank or washed-out image. */
const MIN_INK_RATIO = 0.004
/** Above this share, too much of the image is dark - likely not a plain white background behind the signature. */
const MAX_INK_RATIO = 0.55

/** HEIC/HEIF (the default on iPhone cameras) isn't included - most non-Safari browsers have no built-in decoder for it, so it's rejected upfront with guidance rather than failing opaquely at decode time. */
const HEIC_TYPES = new Set(["image/heic", "image/heif"])

export async function imageFileToTransparentSignature(file: File): Promise<string> {
  if (HEIC_TYPES.has(file.type) || /\.(heic|heif)$/i.test(file.name)) {
    throw new Error(
      "HEIC/HEIF photos aren't supported. On iPhone, switch Camera to \"Most Compatible\" in Settings > Camera > Formats (or share the photo as JPEG), then upload again."
    )
  }
  if (!file.type.startsWith("image/")) {
    throw new Error("Choose an image file (PNG, JPEG, or WEBP).")
  }
  if (file.size > MAX_SOURCE_BYTES) {
    throw new Error("That image is too large - try a smaller photo or scan.")
  }

  const dataUrl = await readFileAsDataUrl(file)
  const img = await loadImage(dataUrl).catch(() => {
    throw new Error("Could not read that image - try a different photo or scan.")
  })

  const { width, height } = fitWithin(img.naturalWidth, img.naturalHeight, MAX_WIDTH, MAX_HEIGHT)

  const canvas = document.createElement("canvas")
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext("2d")
  if (!ctx) {
    throw new Error("Could not process that image.")
  }
  ctx.drawImage(img, 0, 0, width, height)

  const imageData = ctx.getImageData(0, 0, width, height)
  const data = imageData.data
  let inkPixels = 0
  let presencePixels = 0
  for (let i = 0; i < data.length; i += 4) {
    const luminance = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
    const alpha = Math.round(255 - luminance)
    data[i + 3] = alpha
    if (alpha > INK_ALPHA_THRESHOLD) {
      inkPixels++
    }
    if (alpha > PRESENCE_ALPHA_THRESHOLD) {
      presencePixels++
    }
  }

  const totalPixels = width * height
  if (presencePixels / totalPixels < MIN_INK_RATIO) {
    throw new Error(
      "We couldn't find a clear signature in that image. Sign your name in dark ink on a plain white sheet of paper, then upload a clear, well-lit photo or scan of it."
    )
  }
  if (inkPixels / totalPixels > MAX_INK_RATIO) {
    throw new Error(
      "That image doesn't look like a signature on a plain white background. Use a clear, well-lit photo or scan with a plain white background behind the signature."
    )
  }

  ctx.putImageData(imageData, 0, 0)

  const out = canvas.toDataURL("image/png")
  return out.slice(out.indexOf(",") + 1)
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error("Could not read that file."))
    reader.readAsDataURL(file)
  })
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error("Could not read that image."))
    img.src = src
  })
}

function fitWithin(width: number, height: number, maxWidth: number, maxHeight: number) {
  const scale = Math.min(maxWidth / width, maxHeight / height, 1)
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}
