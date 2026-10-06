import type { LucideIcon } from "lucide-react"
import {
  Download,
  FolderInput,
  FolderPlus,
  FolderX,
  History,
  Pencil,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  Trash2,
  Upload,
} from "lucide-react"

import type { DashboardActivity } from "@/lib/dashboard/api"

export interface ActivityLook {
  verb: string
  icon: LucideIcon
  color: string
}

/** What each recorded action reads as, and its icon and colour - the actions DashboardService picks. */
const ACTIONS: Record<string, ActivityLook> = {
  DOCUMENT_UPLOADED: {
    verb: "uploaded",
    icon: Upload,
    color: "var(--primary)",
  },
  DOCUMENT_DOWNLOADED: { verb: "downloaded", icon: Download, color: "#2b579a" },
  DOCUMENT_DELETED: {
    verb: "moved to the recycle bin",
    icon: Trash2,
    color: "var(--rust)",
  },
  DOCUMENT_RESTORED: {
    verb: "restored",
    icon: RotateCcw,
    color: "var(--primary)",
  },
  DOCUMENT_MOVED: { verb: "moved", icon: FolderInput, color: "#8764b8" },
  DOCUMENT_VERSION_RESTORED: {
    verb: "restored an older version of",
    icon: History,
    color: "var(--gold)",
  },
  DOCUMENT_ACCESS_CHANGED: {
    verb: "changed who can open",
    icon: ShieldCheck,
    color: "var(--gold)",
  },
  DOCUMENT_QUARANTINED: {
    verb: "quarantined",
    icon: ShieldAlert,
    color: "var(--rust)",
  },
  FOLDER_CREATED: {
    verb: "created the folder",
    icon: FolderPlus,
    color: "var(--primary)",
  },
  FOLDER_RENAMED: {
    verb: "renamed the folder",
    icon: Pencil,
    color: "#4f7a8a",
  },
  FOLDER_MOVED: {
    verb: "moved the folder",
    icon: FolderInput,
    color: "#8764b8",
  },
  FOLDER_DELETED: {
    verb: "deleted the folder",
    icon: FolderX,
    color: "var(--rust)",
  },
  FOLDER_ACCESS_CHANGED: {
    verb: "changed who can open the folder",
    icon: ShieldCheck,
    color: "var(--gold)",
  },
}

const FALLBACK: ActivityLook = {
  verb: "updated",
  icon: History,
  color: "var(--ink-faint)",
}

export function activityLook(action: string): ActivityLook {
  return ACTIONS[action] ?? FALLBACK
}

/** "You" for the signed-in user's own actions, otherwise their name. */
export function activityWho(
  entry: DashboardActivity,
  currentUserId?: string
): string {
  return entry.actorId && entry.actorId === currentUserId
    ? "You"
    : (entry.actorName ?? "Someone")
}
