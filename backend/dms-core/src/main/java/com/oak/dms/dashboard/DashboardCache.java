package com.oak.dms.dashboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Keeps the dashboard's answers in Redis for a short while.
 * <p>
 * The figures scan every document, version and audit event the caller may see: about 16 s of
 * database time at 3.7 million documents, and it is paid again by every click and every
 * auto-refresh. The answer is the same for the same person for a minute, so it is worked out once
 * and served from here.
 * <p>
 * What makes that safe:
 * <ul>
 *   <li>The key names the organisation and the user - the response carries "my documents" and the
 *       newest documents the caller may open, so one person's answer is never another's.</li>
 *   <li>The key also holds a fingerprint of what the caller may see (the permissions the dashboard
 *       branches on, and which folders are hidden from them and which restricted documents they were
 *       given). Take access away and the next request misses the cache rather than showing figures
 *       and titles the person may no longer see.</li>
 *   <li>One request at a time works a missing answer out; the others wait for it instead of
 *       starting the same 16 s scan 20 times over.</li>
 *   <li>Redis being down changes nothing but speed: every step falls back to working it out.</li>
 * </ul>
 * The cost is staleness: an upload shows up on the dashboard within {@code dms.dashboard.cache-seconds}
 * (default 60; 0 turns the cache off). The document browser itself is never cached.
 */
@Slf4j
@Component
public class DashboardCache {

    /** Versioned so a release that changes the response shape never reads the old one. */
    static final String PREFIX = "dms:dashboard:v1:";

    private static final Duration LOCK_TTL = Duration.ofSeconds(90);
    private static final Duration MAX_WAIT = Duration.ofSeconds(25);
    private static final long POLL_MILLIS = 250;

    private final StringRedisTemplate redis;
    private final DashboardService dashboardService;
    private final DigitalImpactService digitalImpactService;
    private final AuthorizationService authorization;
    private final DocumentAccessService documentAccessService;
    private final Duration ttl;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // A field added by a later release must not make an older entry unreadable.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public DashboardCache(StringRedisTemplate redis, DashboardService dashboardService,
                          DigitalImpactService digitalImpactService, AuthorizationService authorization,
                          DocumentAccessService documentAccessService,
                          @Value("${dms.dashboard.cache-seconds:60}") long cacheSeconds) {
        this.redis = redis;
        this.dashboardService = dashboardService;
        this.digitalImpactService = digitalImpactService;
        this.authorization = authorization;
        this.documentAccessService = documentAccessService;
        this.ttl = Duration.ofSeconds(Math.max(0, cacheSeconds));
    }

    public DashboardResponse dashboard(ZoneId zone, int days) {
        if (ttl.isZero()) {
            return dashboardService.forCaller(zone, days);
        }
        String key = PREFIX + "d:" + scope() + ":" + zone.getId() + ":" + DashboardService.period(days);
        return cached(key, DashboardResponse.class, () -> dashboardService.forCaller(zone, days));
    }

    public DigitalImpactResponse impact(ZoneId zone, int days, LocalDate from, LocalDate to) {
        if (ttl.isZero()) {
            return digitalImpactService.forCaller(zone, days, from, to);
        }
        String key = PREFIX + "i:" + scope() + ":" + zone.getId() + ":" + days + ":" + from + ":" + to;
        return cached(key, DigitalImpactResponse.class, () -> digitalImpactService.forCaller(zone, days, from, to));
    }

    <T> T cached(String key, Class<T> type, Supplier<T> compute) {
        T hit = read(key, type);
        if (hit != null) {
            return hit;
        }
        boolean leader = tryLock(key);
        if (!leader) {
            long deadline = System.nanoTime() + MAX_WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                hit = read(key, type);
                if (hit != null) {
                    return hit;
                }
            }
            // The one working it out has failed or is taking too long: do it here rather than hang.
        }
        try {
            T value = compute.get();
            write(key, value);
            return value;
        } finally {
            if (leader) {
                unlock(key);
            }
        }
    }

    /** Organisation, user and what they may see - everything the answer depends on besides the period. */
    private String scope() {
        UUID tenant = TenantContext.getTenantId();
        UUID user = TenantContext.getUserId();
        return tenant + ":" + user + ":" + fingerprint();
    }

    private String fingerprint() {
        boolean organisation = authorization.hasPermission(PermissionCodes.ABDR_VIEW_AUDIT_HISTORY);
        boolean documents = authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW);
        boolean quarantine = authorization.hasPermission(PermissionCodes.DOCM_QUARANTINE_VIEW);
        StringBuilder basis = new StringBuilder()
                .append(organisation).append('|').append(documents).append('|').append(quarantine);
        if (documents) {
            DocumentAccessView access = documentAccessService.forCaller();
            basis.append('|').append(access.seesEverything());
            if (!access.seesEverything()) {
                basis.append('|');
                sorted(access.folders().hiddenFolderIds().stream()).forEach(id -> basis.append(id).append(','));
                basis.append('|');
                sorted(access.grantedIds().stream()).forEach(id -> basis.append(id).append(','));
            }
        }
        return sha256(basis.toString());
    }

    private static Stream<UUID> sorted(Stream<UUID> ids) {
        return ids.sorted();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }

    private <T> T read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : mapper.readValue(json, type);
        } catch (Exception e) {
            // A miss, not a failure: Redis being down or an entry being unreadable only costs the scan.
            log.warn("Dashboard cache read failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Dashboard cache write failed for {}: {}", key, e.getMessage());
        }
    }

    /** True when this request should work the answer out: it took the lock, or Redis can't say. */
    private boolean tryLock(String key) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":lock", "1", LOCK_TTL));
        } catch (Exception e) {
            return true;
        }
    }

    private void unlock(String key) {
        try {
            redis.delete(key + ":lock");
        } catch (Exception e) {
            // The lock expires by itself.
        }
    }
}
