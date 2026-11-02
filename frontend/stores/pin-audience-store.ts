import { create } from "zustand"

import type { PinTarget } from "@/lib/pins/use-pins"

/**
 * Which document the audience sheet is open for, if any. One sheet is mounted once in the dashboard
 * shell, and anything that can pin - the pin button, a row's menu, the pinned list - just calls
 * `open(row)`, so no screen has to host its own copy of the sheet.
 */
interface PinAudienceState {
  target: PinTarget | null
  open: (target: PinTarget) => void
  close: () => void
}

export const usePinAudienceStore = create<PinAudienceState>((set) => ({
  target: null,
  open: (target) => set({ target }),
  close: () => set({ target: null }),
}))
