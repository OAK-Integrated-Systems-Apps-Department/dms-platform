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
 */

const MAX_WIDTH = 420
const MAX_HEIGHT = 160
/** Rejected before ever touching the canvas - a multi-megapixel photo would be slow to process for no benefit at this output size. */
const MAX_SOURCE_BYTES = 8_000_000

export async function imageFileToTransparentSignature(file: File): Promise<string> {
  if (!file.type.startsWith("image/")) {
    throw new Error("Choose an image file (PNG or JPEG).")
  }
  if (file.size > MAX_SOURCE_BYTES) {
    throw new Error("That image is too large - try a smaller photo or scan.")
  }

  const dataUrl = await readFileAsDataUrl(file)
  const img = await loadImage(dataUrl)
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
  for (let i = 0; i < data.length; i += 4) {
    const luminance = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
    data[i + 3] = Math.round(255 - luminance)
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
