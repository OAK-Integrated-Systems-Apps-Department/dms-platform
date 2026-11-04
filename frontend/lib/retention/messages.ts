import { ApiError, friendlyMessage } from "@/lib/api-error"

/**
 * The text of a failed retention / compliance action, for a toast.
 *
 * The server's own message is already specific (it names the document, the rule, the date or what to do next), so it is shown
 * as is. The one exception is a 403: the generic "you don't have permission" says nothing, so name the permission that is missing.
 */
export function retentionError(error: unknown, fallback: string, permission?: { action: string; needs: string }): string {
  if (permission && error instanceof ApiError && error.status === 403) {
    return `You need the "${permission.needs}" permission to ${permission.action}.`
  }
  return friendlyMessage(error, fallback)
}
