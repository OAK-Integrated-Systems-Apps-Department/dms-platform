"use client"

import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { LogOut } from "lucide-react"

import {
  DocumentViewer,
  DownloadButton,
  ItemBrowser,
  PublicFrame,
} from "@/components/sharing/public-viewer"
import { Badge } from "@/components/ui/badge"
import { LoadingState } from "@/components/ui/loading-state"
import {
  fetchPortalItems,
  fetchPortalMe,
  portalContentUrl,
  type PortalItem,
} from "@/lib/sharing/api"

/** What a signed-in guest sees: their granted folders and documents, navigable, each at the access they were given. */
export function PortalBrowser({
  token,
  onSignOut,
  onExpired,
}: {
  token: string
  onSignOut: () => void
  onExpired: () => void
}) {
  const [parent, setParent] = useState<string | undefined>(undefined)
  const [viewing, setViewing] = useState<PortalItem | null>(null)

  const me = useQuery({
    queryKey: ["portal", "me", token],
    queryFn: () => fetchPortalMe(token),
    retry: false,
  })
  const items = useQuery({
    queryKey: ["portal", "items", token, parent ?? "root"],
    queryFn: () => fetchPortalItems(token, parent),
    retry: false,
  })

  // A guest token that stops working (expired, account disabled, grant removed) sends them back to sign in.
  const failed = me.isError || items.isError
  useEffect(() => {
    if (failed) onExpired()
  }, [failed, onExpired])

  return (
    <PublicFrame
      title={viewing?.name ?? me.data?.organisation ?? "Client portal"}
      subtitle={me.data ? `Signed in as ${me.data.email}` : undefined}
      actions={
        <>
          {viewing && viewing.access === "download" && (
            <DownloadButton href={portalContentUrl(token, viewing.id, true)} />
          )}
          {viewing && (
            <button
              type="button"
              onClick={() => setViewing(null)}
              className="h-[38px] rounded-btn border border-border px-4 text-[13px] font-semibold hover:bg-muted"
            >
              Back
            </button>
          )}
          <button
            type="button"
            onClick={onSignOut}
            className="inline-flex h-[38px] items-center gap-2 rounded-btn border border-border px-4 text-[13px] font-semibold hover:bg-muted"
          >
            <LogOut size={15} />
            Sign out
          </button>
        </>
      }
    >
      {viewing ? (
        <DocumentViewer
          name={viewing.name}
          mimeType={viewing.mimeType}
          src={portalContentUrl(token, viewing.id, false)}
        />
      ) : items.isPending ? (
        <LoadingState message="Loading what's shared with you…" />
      ) : items.data ? (
        <ItemBrowser
          rootLabel="Shared with you"
          breadcrumb={items.data.breadcrumb}
          items={items.data.items}
          onOpenRoot={() => setParent(undefined)}
          onOpenFolder={setParent}
          onOpenDocument={setViewing}
          renderExtra={(item) => (
            <Badge variant="muted">
              {item.access === "download" ? "View & download" : "View only"}
            </Badge>
          )}
        />
      ) : null}
    </PublicFrame>
  )
}
