"use client"

import { useEffect } from "react"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"

/**
 * Tell the user, once, that something failed to load - as a toast like every other error in the app, instead of a
 * message printed into the page. `error` may be an Error or ready-made text; `fallback` is used when it carries no text.
 */
export function useErrorToast(error: unknown, fallback: string) {
  useEffect(() => {
    if (!error) return
    const text = typeof error === "string" ? error : friendlyMessage(error, fallback)
    // Keyed by the text: several things failing for the same reason (a lost connection) show one toast, not one each.
    toast.error(text, { id: text })
  }, [error, fallback])
}
