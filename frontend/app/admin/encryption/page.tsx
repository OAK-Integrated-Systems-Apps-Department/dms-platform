"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  AlertTriangle,
  KeyRound,
  Loader2,
  LockKeyhole,
  RefreshCw,
  RotateCw,
  ShieldCheck,
} from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import {
  ProgressBar,
  StatTile,
  StepCard,
} from "@/components/ui/step-card"
import { FriendlyDate } from "@/components/documents/browser-items"
import { RotationScheduleCard } from "@/components/encryption/rotation-schedule-card"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  fetchEncryptionStatus,
  rotateEncryptionKey,
  startEncryptExisting,
  startRewrap,
  type EncryptionJobDto,
  type EncryptionStatusDto,
} from "@/lib/encryption/api"
import { usePageTitle } from "@/lib/use-page-title"

const STATUS_KEY = ["encryption", "status"] as const

/**
 * Encryption keys (Module DE): every stored document is encrypted with its
 * own data key, wrapped by the master key in the key service (OpenBao
 * Transit in production). Shows the key and coverage, and runs rotation,
 * re-wrapping and the encryption of documents stored before it was on.
 */
export default function EncryptionKeysPage() {
  usePageTitle("Encryption Keys")
  return (
    <DashboardShell initialActiveLabel="Encryption Keys">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Encryption Keys" },
        ]}
      />
      <div className="mb-5 max-w-[760px]">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Encryption keys
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Every document is encrypted with AES-256 before it&apos;s stored,
          using its own key. Those keys are locked by your organisation&apos;s
          own master key, which never leaves the key service - so storage on its
          own holds nothing readable, and no other organisation&apos;s key can
          open your documents.
        </p>
      </div>
      <PermissionGate
        permission={[
          PermissionCodes.DE_KEYS_VIEW,
          PermissionCodes.DE_KEYS_ROTATE,
          PermissionCodes.DE_KEYS_GENERATE,
        ]}
      >
        <EncryptionKeys />
      </PermissionGate>
    </DashboardShell>
  )
}

function EncryptionKeys() {
  const queryClient = useQueryClient()
  const { hasPermission } = usePermissions()
  const canRotate = hasPermission(PermissionCodes.DE_KEYS_ROTATE)
  const canEncrypt = hasPermission(PermissionCodes.DE_KEYS_GENERATE)
  const [confirmRotate, setConfirmRotate] = useState(false)

  const status = useQuery({
    queryKey: STATUS_KEY,
    queryFn: fetchEncryptionStatus,
    // Follow a running job.
    refetchInterval: (query) =>
      query.state.data?.rewrap.running ||
      query.state.data?.encryptExisting.running
        ? 2000
        : false,
  })

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: STATUS_KEY })
  }

  async function run(
    action: () => Promise<unknown>,
    success: string,
    fallback: string
  ) {
    try {
      await action()
      toast.success(success)
    } catch (error) {
      toast.error(friendlyMessage(error, fallback))
    }
    refresh()
  }

  if (status.isPending)
    return <LoadingState message="Loading encryption status…" />
  if (status.isError) {
    return (
      <p className="text-[13px] text-[var(--rust)]">
        {friendlyMessage(status.error, "Could not load the encryption status.")}
      </p>
    )
  }
  const s = status.data
  const unencryptedTotal = s.unencryptedDocuments + s.unencryptedOtherFiles

  return (
    <div className="flex max-w-[1100px] flex-col gap-5">
      <KeyCard status={s} />

      <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Encrypted objects">
          {s.encryptedObjects.toLocaleString()}
        </StatTile>
        <StatTile label="Not on the current key yet">
          {s.objectsOnOlderVersions.toLocaleString()}
        </StatTile>
        <StatTile label="Documents not yet encrypted">
          <span
            className={
              s.unencryptedDocuments > 0 ? "text-[var(--rust)]" : undefined
            }
          >
            {s.unencryptedDocuments.toLocaleString()}
          </span>
        </StatTile>
        <StatTile label="Other files not yet encrypted">
          <span
            className={
              s.unencryptedOtherFiles > 0 ? "text-[var(--rust)]" : undefined
            }
          >
            {s.unencryptedOtherFiles.toLocaleString()}
          </span>
        </StatTile>
      </dl>

      <div className="grid gap-5 lg:grid-cols-2">
        <StepCard
          icon={RotateCw}
          title="Rotate the master key"
          description="Adds a new version of your organisation's key. Everything stored from now on uses it at once; older documents stay readable and can then be re-wrapped onto it. Nothing is re-uploaded, and other organisations aren't affected."
        >
          {canRotate ? (
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setConfirmRotate(true)} disabled={!s.key}>
                <RotateCw />
                Rotate key
              </Button>
              <Button
                variant="outline"
                disabled={
                  !s.key || s.rewrap.running || s.objectsOnOlderVersions === 0
                }
                onClick={() =>
                  void run(
                    startRewrap,
                    "Re-wrapping started.",
                    "Could not start re-wrapping."
                  )
                }
              >
                <RefreshCw />
                Re-wrap {s.objectsOnOlderVersions.toLocaleString()}{" "}
                {s.objectsOnOlderVersions === 1 ? "key" : "keys"}
              </Button>
            </div>
          ) : (
            <NoPermission />
          )}
          <JobProgress
            job={s.rewrap}
            total={s.objectsOnOlderVersions}
            noun="keys re-wrapped"
          />
        </StepCard>

        <StepCard
          icon={LockKeyhole}
          title="Encrypt existing documents"
          description="Documents stored before encryption was turned on are rewritten encrypted, along with quarantined files and export downloads. Each document is checked against the fingerprint taken at upload before the readable copy is removed."
        >
          {canEncrypt ? (
            <Button
              disabled={
                !s.enabled ||
                !s.key ||
                s.encryptExisting.running ||
                unencryptedTotal === 0
              }
              onClick={() =>
                void run(
                  startEncryptExisting,
                  "Encrypting existing documents.",
                  "Could not start encrypting existing documents."
                )
              }
            >
              <LockKeyhole />
              {unencryptedTotal === 0
                ? "Everything is encrypted"
                : `Encrypt ${unencryptedTotal.toLocaleString()} ${unencryptedTotal === 1 ? "file" : "files"}`}
            </Button>
          ) : (
            <NoPermission />
          )}
          <JobProgress
            job={s.encryptExisting}
            total={unencryptedTotal + s.encryptExisting.processed}
            noun="files encrypted"
          />
        </StepCard>
      </div>

      <RotationScheduleCard
        policy={s.rotation}
        canEdit={canRotate}
        onSaved={refresh}
      />

      {Object.keys(s.objectsByKeyVersion).length > 0 && (
        <StepCard icon={KeyRound} title="Objects per key version">
          <ul className="flex flex-col gap-2.5">
            {Object.entries(s.objectsByKeyVersion).map(([version, count]) => (
              <li
                key={version}
                className="grid grid-cols-[150px_1fr_80px] items-center gap-3 text-[13px]"
              >
                <span className="font-medium">
                  Version {version}
                  {s.key && Number(version) === s.key.latestVersion && (
                    <span className="ml-1.5 text-[11px] text-primary">
                      current
                    </span>
                  )}
                </span>
                <ProgressBar
                  value={count}
                  max={s.encryptedObjects}
                  label={`Version ${version}`}
                />
                <span className="text-right text-muted-foreground tabular-nums">
                  {count.toLocaleString()}
                </span>
              </li>
            ))}
          </ul>
        </StepCard>
      )}

      <ConfirmActionDialog
        open={confirmRotate}
        onOpenChange={setConfirmRotate}
        title="Rotate the master key?"
        description="A new version of your organisation's key is created in the key service and used for everything stored from now on. Existing documents stay readable; re-wrap them afterwards to move them onto the new version. Other organisations' keys aren't touched."
        confirmLabel="Rotate key"
        pendingLabel="Rotating…"
        onConfirm={async () => {
          try {
            const key = await rotateEncryptionKey()
            toast.success(`Rotated - now on key version ${key.latestVersion}.`)
            refresh()
            return null
          } catch (error) {
            return friendlyMessage(error, "Could not rotate the key.")
          }
        }}
      />
    </div>
  )
}

function KeyCard({ status }: { status: EncryptionStatusDto }) {
  const key = status.key
  return (
    <section className="rounded-xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)] sm:p-6">
      <div className="flex flex-wrap items-start gap-4">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
        >
          <ShieldCheck size={21} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-[15px] font-semibold">
            {status.enabled
              ? "Encryption at rest is on"
              : "Encryption at rest is off"}
          </h2>
          <p className="mt-1 text-[13px] text-muted-foreground">
            {status.enabled
              ? "Every new upload, import and export is encrypted before it's stored."
              : "New files are stored without encryption. Already-encrypted documents stay encrypted and readable."}
          </p>
        </div>
      </div>
      {key ? (
        <dl className="mt-4 grid gap-3 sm:grid-cols-4">
          <StatTile label="Key service">
            {key.managed ? "OpenBao Transit" : "Development key file"}
          </StatTile>
          <StatTile label="Your organisation's key">
            <span className="truncate font-mono text-[13px]">
              {key.keyName}
            </span>
          </StatTile>
          <StatTile label="Current version">v{key.latestVersion}</StatTile>
          <StatTile label="Oldest usable version">
            v{key.minDecryptionVersion}
          </StatTile>
        </dl>
      ) : (
        <p className="mt-4 flex items-start gap-2 rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={15} className="mt-px shrink-0" />
          The key service can&apos;t be reached: {status.keyError}
        </p>
      )}
      {key && !key.managed && (
        <p className="mt-3 flex items-start gap-2 rounded-lg border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-foreground">
          <AlertTriangle
            size={15}
            className="mt-px shrink-0 text-[var(--gold)]"
          />
          This is the development key file, for local use only. Production keeps
          its master key in OpenBao and refuses to start without it.
        </p>
      )}
    </section>
  )
}

function JobProgress({
  job,
  total,
  noun,
}: {
  job: EncryptionJobDto
  total: number
  noun: string
}) {
  if (!job.startedAt) return null
  return (
    <div className="mt-4 flex flex-col gap-2 rounded-lg border border-border bg-muted/30 px-3.5 py-3">
      <p className="flex items-center gap-2 text-[12.5px]">
        {job.running && (
          <Loader2 size={13} className="animate-spin text-primary" />
        )}
        <span className="font-semibold">
          {job.processed.toLocaleString()} {noun}
        </span>
        {job.failed > 0 && (
          <span className="text-[var(--rust)]">
            · {job.failed.toLocaleString()} failed
          </span>
        )}
        <span className="ml-auto text-muted-foreground">
          {job.running ? (
            "Running…"
          ) : job.finishedAt ? (
            <>
              Finished <FriendlyDate iso={job.finishedAt} />
            </>
          ) : null}
        </span>
      </p>
      {job.running && total > 0 && (
        <ProgressBar
          value={job.processed + job.failed}
          max={total}
          label="Progress"
        />
      )}
      {job.lastError && (
        <p className="text-[12px] text-[var(--rust)]">
          Last problem: {job.lastError}
        </p>
      )}
    </div>
  )
}

function NoPermission() {
  return (
    <p className="text-[12.5px] text-muted-foreground">
      You can see the key status but not change it.
    </p>
  )
}
