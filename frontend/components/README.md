# Component folder structure

```
components/
├── ui/                       Generic, reusable primitives — not tied to any
│   ├── button.tsx            one feature. Same spirit as shadcn's components/ui.
│   ├── toggle.tsx
│   ├── icon-button.tsx
│   ├── dropdown.tsx          Dropdown + MenuRow
│   └── panel.tsx             The "card with a title" shell
│
├── layout/                   The app shell — sidebar, topbar, and the
│   ├── app-sidebar.tsx       component that combines them.
│   ├── app-topbar.tsx
│   └── dashboard-shell.tsx
│
├── dashboard/                 Home-dashboard feature: data + page-specific UI.
│   ├── types.ts               Shared TypeScript shapes
│   ├── data.ts                Mock data (swap for a fetch/query hook later)
│   ├── nav-config.ts          Sidebar nav items/sections
│   ├── file-icon.ts           Maps a document type to a Lucide icon
│   ├── status-stamp.tsx       The ink-stamp Approved/Pending/Draft/Rejected badge
│   ├── stat-card.tsx
│   ├── storage-card.tsx
│   ├── stats-row.tsx          Composes the 4 stat/storage cards
│   ├── welcome-header.tsx
│   ├── panels/
│   │   ├── recent-documents-panel.tsx
│   │   ├── pending-approvals-panel.tsx
│   │   ├── recent-activity-panel.tsx
│   │   └── index.ts           Barrel export for the 3 panels
│   └── index.ts               Barrel export for the whole feature
│
└── theme-provider.tsx          (unchanged, existing project file)
```

## Why it's split this way

- **`ui/`** — things you'd reuse on a totally different page (a settings
  screen, a form). No dashboard-specific data or types.
- **`layout/`** — the page frame. Swap `app-sidebar.tsx` or `app-topbar.tsx`
  independently; `dashboard-shell.tsx` just wires them together.
- **`dashboard/`** — everything specific to the Home dashboard. Each panel
  in `panels/` is self-contained: delete its import from `app/page.tsx` to
  remove it, or copy one as a starting point for a new panel.

## Editing a single piece

- Change the sidebar nav → `components/dashboard/nav-config.ts`
- Change mock data → `components/dashboard/data.ts`
- Change the color theme → `app/globals.css` only
- Remove a dashboard panel → delete its line in `app/page.tsx`
- Add a new panel → create `components/dashboard/panels/my-panel.tsx`
  returning `<Panel title="...">...</Panel>`, export it from
  `panels/index.ts`, and add `<MyPanel />` to `app/page.tsx`.
