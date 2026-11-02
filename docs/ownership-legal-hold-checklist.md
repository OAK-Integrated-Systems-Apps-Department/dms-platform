# Ownership transfer and legal hold: checklist for when a Legal Hold feature exists (DOT alternate flow c, D11)

There is no Legal Hold feature yet. The decision is that **a legal hold never blocks an ownership transfer**: the transfer is allowed and the **full ownership history is preserved**. Today a hold exists only as the seeded (unused) permissions `crm.place-legal-hold` / `crm.lift-legal-hold` and the nullable `audit_events.legal_hold_flag`. No code was added for it. When a Legal Hold feature is built, verify the following.

- [ ] A document under hold can be transferred - single, selected, all, offboarding, holding - and the transfer is **not** refused, skipped or queued.
- [ ] The history row is written, exactly as for any document: previous owner and department, new owner and department, actor, time, reason, batch.
- [ ] The hold itself is untouched by the transfer: still in force, same placed-by and reason, same scope; it follows `document_id`, not the owner.
- [ ] `legal_hold_flag` is set on the `DOCUMENT_OWNERSHIP_TRANSFERRED` event for a held document. For a bulk run the batch summary states how many held documents it contained.
- [ ] The history of a held document **cannot be altered or removed** by the hold's release or by any retention job (`ownership_transfers` stays append-only).
- [ ] A held document in the **Recycle Bin** can be transferred; purging stays blocked by the hold (the existing purge rules are the hold's job, not DOT's).
- [ ] Offboarding an owner of held documents still deactivates the account and moves the documents; the holder of the hold is told if the hold names the owner.
- [ ] The new owner's access (`Also give the new owner access`) works as for any document, including the Restricted conversion.
- [ ] Who may transfer a held document is unchanged: tenant admins, or the head of the owner's department for documents they can open.
- [ ] Re-run the transfer, bulk, security and concurrency suites with held documents in the data set: `OwnershipTransferIT`, `OwnershipBulkIT`, `OwnershipSecurityIT`, `OwnershipOffboardingIT`, `OwnershipConcurrencyIT`.
- [ ] Update `docs/ownership-user-guide.md` (Legal hold section) and `docs/events/ownership-audit-events.md`.
