import type { LucideIcon } from "lucide-react"
import {
  Eye,
  FolderTree,
  History,
  LayoutDashboard,
  ShieldCheck,
  Trash2,
  Upload,
  Users,
} from "lucide-react"

/**
 * What the Help page says - kept apart from the page so it's easy to keep up
 * to date. Every answer describes how the app behaves today; change it here
 * when a feature changes.
 */

export interface HelpArticle {
  id: string
  question: string
  /** Paragraphs; a line starting with "• " is shown as a bullet. */
  answer: string[]
  /** Extra words people might search for. */
  keywords?: string
}

export interface HelpTopic {
  id: string
  title: string
  summary: string
  icon: LucideIcon
  color: string
  articles: HelpArticle[]
}

export const HELP_TOPICS: HelpTopic[] = [
  {
    id: "getting-started",
    title: "Getting started",
    summary: "Find your way around the dashboard and your documents.",
    icon: LayoutDashboard,
    color: "var(--primary)",
    articles: [
      {
        id: "dashboard",
        question: "What does the dashboard show?",
        answer: [
          "The dashboard summarises the documents you can open: how many there are, how many are yours, what's been shared with you and how much storage they use, compared with the previous period.",
          "Use the 7, 30 or 90 days switch at the top to change the period. Below the figures you'll find your recent documents, what needs your attention, recent activity and a breakdown by file type, document type and folder.",
        ],
        keywords: "home overview figures period",
      },
      {
        id: "search",
        question: "How do I find a document?",
        answer: [
          "Type in the search box at the top of any page. It searches the titles of every document you can open, in every folder, as you type.",
          "Use the arrow keys to move through the results and Enter to open one, or click a result to preview it.",
        ],
        keywords: "find look up title",
      },
      {
        id: "only-what-you-can-see",
        question: "Why can't I see some documents or menu items?",
        answer: [
          "What you see depends on your role. Menu items, buttons and documents only appear when your role allows them, and folders or documents with their own access list are only shown to the people and roles on it.",
          "If you need access to something, ask your system administrator.",
        ],
        keywords: "permission role missing hidden access denied",
      },
    ],
  },
  {
    id: "uploading",
    title: "Uploading documents",
    summary: "Add files and folders, and what happens to them.",
    icon: Upload,
    color: "#2b579a",
    articles: [
      {
        id: "how-to-upload",
        question: "How do I upload documents?",
        answer: [
          "Open My Documents and either use Upload, or drag files from your computer and drop them anywhere on the page. You can also drop whole folders - their folder structure is recreated.",
          "Progress is shown in the panel at the bottom right of the screen, where you can also cancel or retry an upload.",
        ],
        keywords: "add drag drop folder upload",
      },
      {
        id: "file-types",
        question: "Which file types can I upload?",
        answer: [
          "Documents (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, text, CSV, Markdown, XML, JSON), emails (.eml, .msg), Visio and AutoCAD drawings, images (PNG, JPG, GIF, BMP, WEBP, HEIC, TIFF) and ZIP archives.",
          "See the full list in the quick reference below. Files whose content doesn't match their extension are refused.",
        ],
        keywords: "formats extension allowed supported",
      },
      {
        id: "size-limit",
        question: "How large can a file be?",
        answer: [
          "There's no size limit. Files over 2 GB are checked for viruses in parts, so they take longer - they show as being scanned until the check finishes.",
        ],
        keywords: "size limit big large maximum",
      },
      {
        id: "scanning",
        question: "Why does an upload say it's being scanned?",
        answer: [
          "Every file is checked for viruses and malware before it's added. A file only appears in your documents once it has passed.",
          "• Files with malware are held in quarantine and never added.",
          "• Password-protected files can't be checked, so they can't be uploaded - remove the password first.",
          "• A file identical to the document's current version is refused, as there's nothing new to add.",
        ],
        keywords:
          "virus malware quarantine blocked refused password protected duplicate",
      },
      {
        id: "zip",
        question: "Can I upload ZIP files?",
        answer: [
          "Yes. A ZIP is stored exactly as uploaded and is never unpacked - but every file inside it is scanned. Damaged, empty or password-protected ZIPs are refused.",
        ],
        keywords: "zip archive compressed",
      },
    ],
  },
  {
    id: "organising",
    title: "Folders and organising",
    summary: "Create folders, move documents and keep things tidy.",
    icon: FolderTree,
    color: "var(--gold)",
    articles: [
      {
        id: "folders",
        question: "How do I create and manage folders?",
        answer: [
          "In My Documents, create a folder from the toolbar. Open a folder's menu to rename it, move it or manage who can open it.",
        ],
        keywords: "new folder rename",
      },
      {
        id: "move",
        question: "How do I move documents or folders?",
        answer: [
          "Drag them onto a folder - or onto a folder in the path at the top - and drop. You can also use Move to from an item's menu. After a move, Undo puts things back.",
        ],
        keywords: "drag drop move undo",
      },
      {
        id: "templates",
        question: "What are document templates?",
        answer: [
          "Templates are ready-made files for a document type, such as a contract or invoice layout. Use New from template in My Documents to download one or to copy it straight into a folder.",
        ],
        keywords: "template new from template",
      },
    ],
  },
  {
    id: "viewing",
    title: "Viewing and downloading",
    summary: "Preview files in the browser and download them.",
    icon: Eye,
    color: "#8764b8",
    articles: [
      {
        id: "preview",
        question: "Which files can I preview?",
        answer: [
          "Click a document to preview it without downloading. PDFs, Word and Excel files, images (including HEIC and TIFF), emails and text open in the browser. PowerPoint presentations open as slides, and older Office formats are converted to PDF for the preview.",
          "AutoCAD drawings and ZIP archives can't be previewed - download them to open them.",
        ],
        keywords: "open view preview slides",
      },
      {
        id: "slides",
        question: "How do I move through a presentation?",
        answer: [
          "Use the previous and next buttons under the slide, click a thumbnail, or use the arrow keys, Page Up and Page Down, Home and End.",
        ],
        keywords: "powerpoint pptx slides keyboard",
      },
      {
        id: "download",
        question: "How do I download documents or a whole folder?",
        answer: [
          "Choose Download from a document's or folder's menu. A folder - or several selected documents - downloads as one ZIP that keeps the folder structure. Progress appears in the panel at the bottom right.",
        ],
        keywords: "download zip folder save",
      },
    ],
  },
  {
    id: "versions",
    title: "Versions",
    summary: "Upload new versions and go back to older ones.",
    icon: History,
    color: "#4f7a8a",
    articles: [
      {
        id: "new-version",
        question: "How do I upload a new version of a document?",
        answer: [
          "Open Version history from the document's menu and upload the new file there. The earlier versions are kept.",
        ],
        keywords: "update replace version",
      },
      {
        id: "restore-version",
        question: "Can I go back to an older version?",
        answer: [
          "Yes, if your role allows it. In Version history, preview any older version and restore it - it's added again as the newest version, so nothing is lost.",
        ],
        keywords: "restore previous older rollback",
      },
    ],
  },
  {
    id: "sharing",
    title: "Sharing and access",
    summary: "Control who can open folders and documents.",
    icon: Users,
    color: "#c43e1c",
    articles: [
      {
        id: "manage-access",
        question: "How do I control who can open a folder or document?",
        answer: [
          "Choose Manage access from its menu. Add people or roles and choose what they can do - view, edit or manage. A document can follow its folder's access, or have its own list.",
        ],
        keywords: "share permission access restricted",
      },
      {
        id: "shared-with-me",
        question: "Where do I find documents shared with me?",
        answer: [
          "Open Shared With Me from the sidebar. It lists the folders and documents people have given you access to, by name or through one of your roles - wherever they're filed.",
        ],
        keywords: "shared with me",
      },
    ],
  },
  {
    id: "recycle-bin",
    title: "Deleting and restoring",
    summary: "What happens to deleted documents.",
    icon: Trash2,
    color: "var(--rust)",
    articles: [
      {
        id: "deleted",
        question: "What happens when I delete a document?",
        answer: [
          "It moves to the Recycle Bin, where it can be restored. Documents in the Recycle Bin are removed for good after your organisation's retention period - 30 days unless your administrator has changed it.",
        ],
        keywords: "delete remove recycle bin trash restore",
      },
    ],
  },
  {
    id: "security",
    title: "Security and privacy",
    summary: "How your documents are protected.",
    icon: ShieldCheck,
    color: "var(--primary)",
    articles: [
      {
        id: "protection",
        question: "How are my documents protected?",
        answer: [
          "Every upload is scanned for malware before it's stored, files are encrypted at rest, and access is checked on every request. Views, downloads and changes are recorded in the audit log.",
        ],
        keywords: "encryption audit secure safe",
      },
    ],
  },
]

/** The file types the app accepts (dms.ingestion.allowed-file-types), grouped for reading. */
export const FILE_TYPES: { group: string; types: string[] }[] = [
  {
    group: "Documents",
    types: ["pdf", "doc", "docx", "odt", "rtf", "txt", "md"],
  },
  { group: "Spreadsheets", types: ["xls", "xlsx", "ods", "csv", "tsv"] },
  { group: "Presentations", types: ["ppt", "pptx", "odp"] },
  {
    group: "Images",
    types: [
      "png",
      "jpg",
      "jpeg",
      "gif",
      "bmp",
      "webp",
      "heic",
      "heif",
      "tif",
      "tiff",
    ],
  },
  { group: "Email", types: ["eml", "msg"] },
  { group: "Drawings", types: ["vsd", "vsdx", "odg", "dwg"] },
  { group: "Data and archives", types: ["xml", "json", "zip"] },
]

export const SHORTCUTS: { keys: string[]; action: string }[] = [
  { keys: ["↑", "↓"], action: "Move through search results" },
  { keys: ["Enter"], action: "Open the highlighted search result" },
  { keys: ["Esc"], action: "Close the search results" },
  { keys: ["←", "→"], action: "Previous or next slide in a presentation" },
  { keys: ["Home", "End"], action: "First or last slide" },
  { keys: ["Ctrl", "scroll"], action: "Zoom a preview in or out" },
]
