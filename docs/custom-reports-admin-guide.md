# Reports - administrator guide

Administration > **Reports** (permission *Configure reports*). Six tabs. Each shows the platform ceiling next to a limit; you can set a lower value, never a higher one. Leaving a value empty follows the platform value (saving a tab stores today's effective values - see "Saving").

## Permissions

| Permission | Allows |
|---|---|
| Generate custom reports - run / build | Run saved reports / create and edit them |
| Manage report templates - create, update, delete, publish | Own templates; publish official ones |
| Schedule automated reports - create, update/cancel | Own schedules |
| Configure reports | This administration area |
| View all runs | Run history for the organisation: who, what, when, status - never the files |

## Tabs

- **General** - turn Reports and scheduling on or off for the organisation.
- **Fields** - which fields people can filter, show and sort by, and their display names. Disabling a field affects saved reports and schedules that use it: they show a warning, and scheduled runs are skipped until fixed. Custom metadata fields appear here once enabled.
- **Official templates** - reports published to everyone or to an audience. A *locked* report cannot be edited by others; only its marked filters can change at run time. Unpublish or edit in the builder.
- **Schedules & delivery** - allowed channels (in-app, email), attachments and their size limit, external recipients and the allowed-domain list, limits per person / organisation, recipients per schedule, failure rules (pause after N failures, who is notified), catch-up window, skipping empty scheduled reports. The list of all schedules lets you pause, resume, cancel or **reassign** one (for example when the owner leaves).
- **Output & branding** - allowed formats and the default, CSV delimiter and byte-order mark, date and number format, time zone and language, PDF page size and orientation, header, footer, confidentiality notice, logo, and whether to print the filter summary and the requester. **Preview sample** makes a one-page PDF or CSV from invented rows under the settings on screen (before saving); no organisation data is used.
- **Limits** - rows per run (and for PDF), what happens over the limit (stop with an error, or cut the file and say so), concurrent runs, file retention, templates per person.

## Saving

Each tab sends the full settings with its own changes merged in, so a tab never erases another's values. The confirmation shows old and new values; problems from the server appear next to the field. Every change is audited (`REPORT_SETTINGS_UPDATED`, `REPORT_FIELD_SETTINGS_UPDATED`).

## Things to know

- A scheduled report runs as its owner; if the owner is deactivated or loses the run permission the run is skipped and the schedule pauses after repeated failures. Reassign it to keep it going.
- Recipients each get only what they can see. External recipients are checked against the allow-list and audited as external deliveries.
- Reports are a way for data to leave the system: generation, downloads and deliveries are audited (see `events/report-audit-events.md`) without filter values or addresses.
- Platform ceilings (`DMS_REPORTS_*`) are set by the platform operator - see `deployment.md`.
- Hourly schedules appear only when the platform allows them *and* you set the minimum schedule interval to hourly; an hourly schedule fires every hour at the minute of its time. If you switch back to daily, existing hourly schedules are skipped with a clear reason.
- When you reassign a schedule, the new owner is notified.
- A person who gets a report by both in-app and email receives one email (the report), not two.
- Disabling a field shows how many official reports and live schedules use it; people's private reports are counted but never named.
- PDF files with Sinhala text need a font that contains those characters; complex-script shaping is not supported by the current PDF library.
