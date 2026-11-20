# Reports - user guide

Open **Reports** in the Documents menu. You see it when you have permission to run or build reports.

## What a report contains

Only documents you can open yourself. If you cannot see a folder or document, it is not in your report and not in any count. A note on the page says when results are limited to your access.

## Build a report

Choose **New report**. The builder has four steps on one page:

1. **Filters** - for example "Document type is any of Contract", "Owner department is Finance", "Created in the last 30 days". A sentence at the side restates your filters in words. Dates can be fixed or relative.
2. **Columns** - pick the columns, drag them (or use the keyboard buttons) to reorder.
3. **Sort and group** - one sort order, and at most one group-by.
4. **Run** - pick CSV, Excel or PDF.

The preview shows the number of matching documents and a few sample rows while you work. If nothing matches you will see "No data found" - adjust the filters.

**Save** gives the report a name so you can run it again. **Save and schedule** also opens the schedule form.

## Run a report

From **My reports** choose **Run**. The file is prepared in the background; open **Run history** to see its status and download it. If a run fails, choose **Retry**. You can **Cancel** a run that is still waiting or running. Files expire after the number of days your organisation sets.

A *locked* report (published by your administrator) lets you change only the filters it marks as editable.

## Share, duplicate, delete

- **Share** gives other people or roles read or edit access to your report.
- **Duplicate** makes your own copy of any report you can see (a good way to start from an official one).
- **Delete** removes it; schedules built on it are cancelled. Files already made stay until they expire.

## Schedule a report

Choose **Schedule** (or use the **Schedules** tab). Pick daily, weekly or monthly, a time and a time zone, a format, and who receives it: people, roles, departments or - if your organisation allows - external email addresses. The form states whose access applies.

Good to know:

- The report runs **as you**, and your access is checked each time. If you leave, lose the permission, or the report or one of its fields is removed, the run is skipped and you are told why.
- Each recipient receives only what **they** may see, so two people can receive different files from the same schedule.
- After several failed runs in a row the schedule pauses; fix the cause and choose **Resume**.
- You can **Pause**, **Resume**, **Run now** or **Cancel** a schedule at any time.
- Day of month is 1-28 or "last day" so every month has a run.

## Common messages

| Message | What to do |
|---|---|
| Too many rows | Narrow the filters, or ask your administrator about the row limit. |
| Too many queued reports | Wait for earlier runs to finish. |
| Report is read-only / locked | Duplicate it to make an editable copy. |
| Report name already used | Choose another name. |
| Not ready / no file | The file is still being made, or it has expired - run it again. |

## Who can do what

What you see on the Reports page follows your permissions. The server checks them again on every action.

| You can... | Permission needed |
|---|---|
| Open Reports, run and download reports | Run custom reports (given by default to anyone who can view documents) |
| Build, edit, duplicate and schedule your own reports | Build custom reports |
| Create, edit, delete and publish organisation templates | Manage report templates (create / update / delete / publish) |
| See every person's run history, change settings, limits, output and branding | Tenant admin, or the matching admin permission |

A tenant admin has all of these. Whatever your permissions, a report only ever contains documents you may open yourself.

## Official (organisation) reports

An administrator, or anyone with the publish permission, can turn a saved report into an **official** one for the whole organisation:

1. Create the report as above (**Reports > My reports > New report**), or from **Administration > Reports > Official templates > New report**.
2. Open **Administration > Reports > Official templates**. Under **Your reports** choose **Publish**.
3. Choose whether to **lock** it. Tick the filters people may still change.
4. Choose the **audience**: leave it empty for everyone, or pick people, roles or departments.
5. Choose **Publish**. People in the audience now see it under My reports as *Official* and can run and download it.

Use **Change publishing** to alter the lock or audience, **Edit in builder** to change what it shows, and **Unpublish** to make it private again. Publishers always see official reports in this list, even ones published to an audience they are not in.
