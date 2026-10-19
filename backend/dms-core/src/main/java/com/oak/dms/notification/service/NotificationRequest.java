package com.oak.dms.notification.service;

import lombok.Builder;
import lombok.Getter;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/**
 * One call to {@link NotificationService#notify}. The default title/body/link
 * apply to every recipient unless {@link #perRecipientContent} overrides them.
 * {@link #dedupeKey} is the base idempotency key: the unique index already
 * includes the recipient, so two recipients may share the same base key. A
 * null dedupeKey means no de-duplication (a random key is generated per
 * recipient) - right for event-driven callers, whose event fires once per
 * transaction and where a repeat (a second delegation, a resubmission) is a
 * genuinely new notification.
 *
 * <h3>Access-aware content (decision 7)</h3>
 * When {@link #documentId} is set, the service checks, per recipient, whether
 * they can open that document (DocumentAccessService, including workflow-scoped
 * read). Recipients who can get the title/body/link as given; the others get
 * {@link #neutralTitle}/{@link #neutralBody} (which must not mention the
 * document's title) and {@link #neutralLinkUrl} (null = no link). Pass
 * the document id only when the content names or links the document.
 * The check runs once per call for all recipients, so batch the recipients of
 * one event into one call.
 */
@Getter
@Builder
public class NotificationRequest {

    private final UUID tenantId;
    private final Collection<UUID> recipientUserIds;
    private final String categoryCode;

    private final String title;
    private final String body;
    private final String linkUrl;

    private final String objectType;
    private final UUID objectId;

    private final UUID actorUserId;
    /** Defaults to the category's module when null. */
    private final String sourceModule;
    private final String dedupeKey;

    /** The document the content names or links; makes the content access-aware per recipient. */
    private final UUID documentId;
    private final String neutralTitle;
    private final String neutralBody;
    /** Link for recipients who cannot open the document; null = no link. */
    private final String neutralLinkUrl;
    /**
     * Also require {@code docm.documents.view} to count as "can open". Off by default: the check is
     * DocumentAccessService's (folder/document access incl. workflow-scoped read), which is what the people
     * a workflow is waiting on rely on. Mentions turn it on - decision 10 has always said a mentioned person
     * could open the document only with the permission AND the access.
     */
    private final boolean requireViewPermission;

    /** Optional per-recipient content override, applied after the access check. */
    private final Function<UUID, RecipientContent> perRecipientContent;

    /** A per-recipient override; any null field falls back to the request default. */
    public record RecipientContent(String title, String body, String linkUrl) {
    }
}
