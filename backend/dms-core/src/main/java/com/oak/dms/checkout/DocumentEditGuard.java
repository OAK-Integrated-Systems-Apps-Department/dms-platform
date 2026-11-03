package com.oak.dms.checkout;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The check-out and legal-hold half of "may this document be changed right now" - called next
 * to {@code DocumentSignatureLockService#guard} at every place that changes a document (new
 * version, restore, metadata, move, delete), so editing can never get around a lock by taking a
 * different route than check-in.
 *
 * <p>Two rules: a legal hold blocks everyone, and an unexpired check-out blocks everyone except
 * the person holding it (they are the one editing). An EXPIRED check-out blocks nobody - see
 * {@link Document#isCheckedOutAt}.
 */
@Component
@RequiredArgsConstructor
public class DocumentEditGuard {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    /**
     * {@link #guard(Document)} for a caller that only has the id - the manual metadata edit. Loads the
     * document within the caller's tenant (another tenant's id is simply "not found", same as everywhere).
     */
    public void guard(UUID documentId) {
        guard(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId)));
    }

    /** @throws CheckoutException 409 DOCUMENT_LEGAL_HOLD or DOCUMENT_CHECKED_OUT */
    public void guard(Document document) {
        if (document.isLegalHold()) {
            throw CheckoutException.conflict("DOCUMENT_LEGAL_HOLD",
                    "\"" + document.getTitle() + "\" is under legal hold and cannot be changed.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (document.isCheckedOutAt(now)
                && !Objects.equals(document.getCheckedOutBy(), TenantContext.getUserId())) {
            throw CheckoutException.conflict("DOCUMENT_CHECKED_OUT",
                    "\"" + document.getTitle() + "\" is checked out by " + nameOf(document.getCheckedOutBy())
                            + " since " + when(document.getCheckedOutAt()) + ".");
        }
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    /**
     * A moment as people read it in a refusal - "2026-11-03 03:47 UTC", not "2026-11-03T03:47:19.671824Z". Always UTC
     * and says so, because the server cannot know the reader's time zone (the pages show their own local time).
     */
    static String when(OffsetDateTime moment) {
        return moment == null ? "an unknown time" : WHEN.format(moment.withOffsetSameInstant(java.time.ZoneOffset.UTC));
    }

    String nameOf(UUID userId) {
        return userRepository.findById(userId).map(User::getDisplayName).orElse("another user");
    }
}
