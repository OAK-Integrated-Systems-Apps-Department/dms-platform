package com.oak.dms.compliance;

import com.oak.dms.checkout.DocumentEditGuard;
import com.oak.dms.document.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Stops PREMATURE deletion (the module's business objective): when the organisation has turned on "protect from deletion", a
 * document that a retention rule still covers cannot be deleted - by anyone - until its total retention (active + archive
 * period, plus any extension) has ended. The moment it is flagged for review the guard lets go, because that is when an
 * administrator decides about disposal (and disposal itself goes through the same delete). A document no rule covers is not
 * affected, and neither is an organisation that has not switched the setting on. Legal hold is separate and always applies.
 */
@Component
@RequiredArgsConstructor
public class RetentionDeletionGuard {

    private final RetentionSettingsService settings;
    private final RetentionDueDates dueDates;

    /** @throws ComplianceException 409 {@code DOCUMENT_UNDER_RETENTION} naming the rule and the date it can go */
    public void guardDeletion(Document document) {
        if (!settings.forTenant(document.getTenantId()).protectFromDeletion() || document.getReviewFlaggedAt() != null) {
            return;
        }
        Optional<RetentionResolver.Resolution> resolution = dueDates.resolve(document.getTenantId(), document);
        if (resolution.isEmpty() || !resolution.get().reviewDue().isAfter(OffsetDateTime.now())) {
            return;
        }
        RetentionPolicy rule = resolution.get().policy();
        throw ComplianceException.conflict("DOCUMENT_UNDER_RETENTION", "\"" + document.getTitle() + "\" can't be deleted yet: the retention rule \""
                + rule.getName() + "\"" + (rule.getFramework() == null ? "" : " (" + rule.getFramework() + ")")
                + " keeps it until " + DocumentEditGuard.when(resolution.get().reviewDue())
                + ". If it was added by mistake, ask an administrator to change the rule.");
    }
}
