"use client"

import { useCallback, useEffect, useState } from "react"
import { useParams } from "next/navigation"

import {
  AccessDenied,
  DocumentViewer,
  DownloadButton,
  ItemBrowser,
  OpenCard,
  PublicFrame,
} from "@/components/sharing/public-viewer"
import { LoadingState } from "@/components/ui/loading-state"
import {
  fetchLinkInfo,
  fetchLinkListing,
  linkContentUrl,
  openLink,
  type LinkInfo,
  type Listing,
  type ListingItem,
  type OpenResult,
} from "@/lib/sharing/api"

/**
 * Public share-link page (SEA workflow steps 4-5): no session. Shows the link's state, asks for the password when
 * there is one, and only then "opens" the link (which is what counts as a view). Expired, revoked, exhausted and
 * locked links get a friendly access-denied screen, never an error page.
 */
export default function SharedLinkPage() {
  const params = useParams<{ token: string }>()
  const token = params.token

  const [info, setInfo] = useState<LinkInfo | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [opening, setOpening] = useState(false)
  const [openError, setOpenError] = useState<string | null>(null)
  const [denied, setDenied] = useState<OpenResult | null>(null)
  const [accessToken, setAccessToken] = useState<string | null>(null)
  const [listing, setListing] = useState<Listing | null>(null)
  const [viewing, setViewing] = useState<ListingItem | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchLinkInfo(token)
      .then((result) => !cancelled && setInfo(result))
      .catch((e: Error) => !cancelled && setLoadError(e.message))
    return () => {
      cancelled = true
    }
  }, [token])

  const loadFolder = useCallback(
    async (access: string, folderId?: string) => {
      try {
        setListing(await fetchLinkListing(token, access, folderId))
      } catch (e) {
        setLoadError(e instanceof Error ? e.message : "Could not load this folder.")
      }
    },
    [token]
  )

  async function handleOpen(password?: string) {
    setOpening(true)
    setOpenError(null)
    try {
      const result = await openLink(token, password)
      if (result.ok && result.accessToken) {
        setAccessToken(result.accessToken)
        if (info?.targetType === "folder")
          await loadFolder(result.accessToken)
      } else if (result.status === "wrong_password") {
        setOpenError(
          result.attemptsRemaining != null
            ? `That password isn't right. ${result.attemptsRemaining} attempt${result.attemptsRemaining === 1 ? "" : "s"} left.`
            : "That password isn't right."
        )
      } else {
        setDenied(result)
      }
    } catch (e) {
      setOpenError(e instanceof Error ? e.message : "Could not open the link.")
    } finally {
      setOpening(false)
    }
  }

  if (loadError && !info) {
    return <AccessDenied status="unavailable" />
  }
  if (!info) return <LoadingState message="Checking the link…" className="m-6" />
  if (denied) {
    return (
      <AccessDenied status={denied.status} lockedUntil={denied.lockedUntil} />
    )
  }
  if (info.status !== "active") {
    return <AccessDenied status={info.status} lockedUntil={info.lockedUntil} />
  }

  if (!accessToken) {
    return (
      <OpenCard
        heading={info.name ?? "Shared with you"}
        subtitle={
          [
            info.sharedBy && `${info.sharedBy} shared this with you`,
            info.organisation && `from ${info.organisation}`,
            info.expiresAt &&
              `Available until ${new Date(info.expiresAt).toLocaleString()}`,
          ]
            .filter(Boolean)
            .join(" · ") || "Open to continue."
        }
        passwordRequired={info.passwordRequired}
        busy={opening}
        error={openError}
        onOpen={handleOpen}
      />
    )
  }

  const canDownload = info.access === "download"

  if (info.targetType === "document") {
    return (
      <PublicFrame
        title={info.name ?? "Shared document"}
        subtitle={info.sharedBy ? `Shared by ${info.sharedBy}` : undefined}
        actions={
          canDownload && (
            <DownloadButton
              href={linkContentUrl(token, accessToken, { download: true })}
            />
          )
        }
      >
        <DocumentViewer
          name={info.name ?? "Document"}
          src={linkContentUrl(token, accessToken)}
        />
      </PublicFrame>
    )
  }

  // Folder link.
  return (
    <PublicFrame
      title={viewing?.name ?? info.name ?? "Shared folder"}
      subtitle={info.sharedBy ? `Shared by ${info.sharedBy}` : undefined}
      actions={
        viewing ? (
          <>
            {canDownload && (
              <DownloadButton
                href={linkContentUrl(token, accessToken, {
                  documentId: viewing.id,
                  download: true,
                })}
              />
            )}
            <button
              type="button"
              onClick={() => setViewing(null)}
              className="h-[38px] rounded-btn border border-border px-4 text-[13px] font-semibold hover:bg-muted"
            >
              Back to folder
            </button>
          </>
        ) : null
      }
    >
      {viewing ? (
        <DocumentViewer
          name={viewing.name}
          mimeType={viewing.mimeType}
          src={linkContentUrl(token, accessToken, { documentId: viewing.id })}
        />
      ) : listing ? (
        <ItemBrowser
          rootLabel={info.name ?? "Shared folder"}
          // The contract's breadcrumb runs from the shared root down; the root itself is the Home crumb.
          breadcrumb={listing.breadcrumb.slice(1)}
          items={listing.items}
          onOpenRoot={() => loadFolder(accessToken)}
          onOpenFolder={(id) => loadFolder(accessToken, id)}
          onOpenDocument={setViewing}
        />
      ) : (
        <LoadingState message="Loading the folder…" />
      )}
    </PublicFrame>
  )
}
