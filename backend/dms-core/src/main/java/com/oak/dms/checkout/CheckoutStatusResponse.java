package com.oak.dms.checkout;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Whether a document can be edited right now, and if not, why and by whom (CICO-FR-002,
 * CICO-NFR-007). One call answers it, so the UI shows the state without a second request.
 *
 * @param state              {@code available}, {@code checked_out}, {@code legal_hold} or
 *                           {@code signature_locked} - the FIRST reason that applies, in that
 *                           order of what blocks an edit hardest
 * @param checkedOutByMe     true when the caller holds the lock, i.e. may check in or discard
 * @param signatureLocked    true when it carries a digital signature (the DSI lock, a valid signature, or a burned-in
 *                           signature mark); a check-out is refused
 * @param lastEditedById     who made the CURRENT version - the person who last uploaded, checked in or saved it in
 *                           the online editor - with {@code lastEditedAt} (when) and {@code lastEditedVersion} (which
 *                           version: 1 means it was only ever uploaded, never edited). Null/0 when there is no version.
 */
public record CheckoutStatusResponse(
        String state,
        UUID checkedOutById,
        String checkedOutByName,
        OffsetDateTime checkedOutAt,
        OffsetDateTime checkoutExpiresAt,
        boolean checkedOutByMe,
        boolean legalHold,
        String legalHoldReason,
        OffsetDateTime legalHoldAt,
        boolean signatureLocked,
        UUID lastEditedById,
        String lastEditedByName,
        OffsetDateTime lastEditedAt,
        int lastEditedVersion) {
}
