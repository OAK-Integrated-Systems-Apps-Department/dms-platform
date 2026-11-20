package com.oak.dms.sharing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the sharing API - the contract is docs/external-sharing-design.md. */
public final class SharingDtos {

    private SharingDtos() {
    }

    public record Settings(boolean externalSharingEnabled, int defaultExpiryDays, int maxExpiryDays,
                           int maxPasswordAttempts, int lockoutMinutes, int guestInactivityDays,
                           String onReclassify) {
    }

    public record Eligibility(boolean canShare, boolean canOverride, List<String> reasons, boolean confidential) {
    }

    public record CreateLink(String targetType, UUID targetId, String access, OffsetDateTime expiresAt,
                             Integer maxViews, String password, String recipientEmail, String message,
                             Boolean overrideRestriction, String overrideReason) {
    }

    public record Link(UUID linkId, String targetType, UUID targetId, String targetName, String access,
                       String status, boolean passwordProtected, OffsetDateTime expiresAt, Integer maxViews,
                       int viewCount, String recipientEmail, UUID createdBy, String createdByName,
                       OffsetDateTime createdAt, OffsetDateTime lastAccessedAt, boolean needsReview,
                       boolean overridden, OffsetDateTime revokedAt) {
    }

    public record LinkCreated(Link link, String url, boolean emailSent) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    public record InviteGuest(String email, String displayName) {
    }

    public record PatchGuest(String status) {
    }

    public record Guest(UUID guestId, String email, String displayName, String status, String disabledReason,
                        OffsetDateTime lastLoginAt, OffsetDateTime createdAt, long grantCount) {
    }

    public record CreateGrant(String email, String displayName, String targetType, UUID targetId, String access,
                              OffsetDateTime expiresAt, Boolean overrideRestriction, String overrideReason) {
    }

    public record PatchGrant(String access, OffsetDateTime expiresAt) {
    }

    public record GuestGrantView(UUID grantId, UUID guestId, String email, String displayName, String guestStatus,
                                 String targetType, UUID targetId, String targetName, String access,
                                 OffsetDateTime expiresAt, OffsetDateTime revokedAt, OffsetDateTime createdAt) {
    }

    public record SetConfidential(String targetType, UUID targetId, boolean confidential, String reason) {
    }

    public record ConfidentialResult(int flaggedLinks, int revokedLinks, int revokedGuestGrants) {
    }

    public record ShareEventView(UUID eventId, OffsetDateTime at, String type, UUID linkId, UUID guestId,
                                 String actor, String ip, String targetName, String detail) {
    }

    // ---- public share link ----

    public record LinkInfo(String status, boolean passwordRequired, String targetType, String name, String access,
                           OffsetDateTime expiresAt, String organisation, String sharedBy,
                           OffsetDateTime lockedUntil) {
        static LinkInfo denied(String status) {
            return new LinkInfo(status, false, null, null, null, null, null, null, null);
        }
    }

    public record OpenRequest(String password) {
    }

    public record OpenResult(boolean ok, String status, Integer attemptsRemaining, OffsetDateTime lockedUntil,
                             String accessToken, Long expiresInSeconds) {
    }

    public record Crumb(UUID id, String name) {
    }

    public record ListedItem(String type, UUID id, String name, String mimeType, Long sizeBytes,
                             OffsetDateTime updatedAt) {
    }

    public record Listing(Crumb folder, List<Crumb> breadcrumb, List<ListedItem> items) {
    }

    // ---- portal ----

    public record AcceptInvite(String token, String password) {
    }

    public record InviteInfo(boolean valid, String email, String organisation) {
    }

    public record PortalLoginRequest(String email, String password, UUID tenantId) {
    }

    public record Organisation(UUID tenantId, String name) {
    }

    public record PortalLogin(boolean ok, String status, String token, Long expiresInSeconds,
                              List<Organisation> organisations) {
    }

    public record PortalMe(String email, String displayName, String organisation) {
    }

    public record PortalItem(String type, UUID id, String name, String mimeType, Long sizeBytes,
                             OffsetDateTime updatedAt, String access) {
    }

    public record PortalListing(List<Crumb> breadcrumb, List<PortalItem> items) {
    }
}
