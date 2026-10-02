package com.oak.dms.encryption;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.encryption.KeyEncryptionService.KeyInfo;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The key management screen behind encryption at rest (Module DE) - always
 * for the caller's own tenant, whose master key is its own (V57), so a tenant
 * admin can never affect another organisation's data:
 * <ul>
 *   <li><b>status</b> - the tenant's master key, how many of its objects use
 *       each key version, and how many of its files are still stored
 *       unencrypted;</li>
 *   <li><b>rotate</b> - a new version of the tenant's master key; new objects
 *       use it at once (DE-FR-005);</li>
 *   <li><b>re-wrap</b> - moves every one of the tenant's data keys that isn't
 *       on its latest master key onto it: an older version (re-wrapped inside
 *       the key service, in bulk), the shared platform key from before V57, or
 *       another key service. No object is rewritten;</li>
 *   <li><b>encrypt existing</b> - encrypts the tenant's files stored before
 *       encryption: document versions and quarantined files (each verified
 *       against the SHA-256 recorded when stored) and export ZIPs still
 *       available for download.</li>
 * </ul>
 * Jobs run in the background; their state is in the database
 * ({@link EncryptionJobStore}), so they run once however many instances serve
 * the screen, and survive restarts. Each writes an audit event when done.
 */
@Slf4j
@Service
public class EncryptionAdminService {

    static final String REWRAP = "rewrap";
    static final String ENCRYPT_EXISTING = "encrypt-existing";
    private static final int BATCH = 200;

    private final KeyServices keys;
    private final StorageObjectKeyRepository keyRepository;
    private final StorageLocationRepository locations;
    private final ObjectStorageServiceFactory storage;
    private final EncryptionProperties properties;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final StorageEncryption encryption;
    private final EncryptionJobStore jobs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "encryption-admin");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "encryption-admin-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public EncryptionAdminService(KeyServices keys, StorageObjectKeyRepository keyRepository,
                                  StorageLocationRepository locations, ObjectStorageServiceFactory storage,
                                  EncryptionProperties properties, JdbcTemplate jdbc, AuditService auditService,
                                  StorageEncryption encryption, EncryptionJobStore jobs) {
        this.keys = keys;
        this.keyRepository = keyRepository;
        this.locations = locations;
        this.storage = storage;
        this.properties = properties;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.encryption = encryption;
        this.jobs = jobs;
    }

    /** One background job's progress. */
    public record JobStatus(String job, boolean running, long processed, long failed, OffsetDateTime startedAt,
                            OffsetDateTime finishedAt, String lastError) {

        static JobStatus idle(String job) {
            return new JobStatus(job, false, 0, 0, null, null, null);
        }
    }

    /**
     * @param keyError               set when the key service couldn't be asked (the rest still shows)
     * @param objectsOnOlderVersions the tenant's objects not yet on its latest master key - an older
     *                               version, the pre-V57 platform key, or another key service
     * @param unencryptedOtherFiles  quarantined files and downloadable export ZIPs stored before encryption
     */
    public record EncryptionStatus(boolean enabled, KeyInfo key, String keyError, long encryptedObjects,
                                   Map<Integer, Long> objectsByKeyVersion, long objectsOnOlderVersions,
                                   long unencryptedDocuments, long unencryptedOtherFiles, JobStatus rewrap,
                                   JobStatus encryptExisting) {
    }

    public EncryptionStatus status() {
        UUID tenantId = TenantContext.getTenantId();
        String scope = KeyEncryptionService.scopeOf(tenantId);
        KeyEncryptionService primary = keys.primary();
        KeyInfo key = null;
        String keyError = null;
        try {
            key = primary.info(scope);
        } catch (RuntimeException e) {
            keyError = e.getMessage();
        }
        Map<Integer, Long> byVersion = new LinkedHashMap<>();
        for (Object[] row : keyRepository.countByVersion(tenantId, primary.provider(), scope)) {
            byVersion.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        long toRewrap = key == null ? 0
                : keyRepository.countToRewrap(tenantId, primary.provider(), scope, key.latestVersion());
        long other = count(UNENCRYPTED_QUARANTINE, tenantId) + count(UNENCRYPTED_EXPORTS, tenantId);
        return new EncryptionStatus(properties.isEnabled(), key, keyError, keyRepository.countByTenantId(tenantId),
                byVersion, toRewrap, count(UNENCRYPTED_VERSIONS, tenantId), other, jobs.get(tenantId, REWRAP),
                jobs.get(tenantId, ENCRYPT_EXISTING));
    }

    /** A new version of the caller's tenant's master key; returns the key afterwards. */
    public KeyInfo rotate() {
        String scope = KeyEncryptionService.scopeOf(TenantContext.getTenantId());
        KeyEncryptionService primary = keys.primary();
        int before = primary.info(scope).latestVersion();
        KeyInfo after = primary.rotate(scope);
        audit(AuditActionCatalog.ENCRYPTION_KEY_ROTATED, AuditOutcome.SUCCESS, after.keyName(),
                Map.of("fromVersion", before, "toVersion", after.latestVersion(), "key", after.keyName()));
        return after;
    }

    public JobStatus startRewrap() {
        return start(REWRAP, this::runRewrap);
    }

    public JobStatus startEncryptExisting() {
        if (!properties.isEnabled()) {
            throw new EncryptionException(HttpStatus.CONFLICT, "ENCRYPTION_DISABLED", "Encryption at rest is turned off.");
        }
        return start(ENCRYPT_EXISTING, this::runBackfill);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        heartbeats.shutdownNow();
    }

    // ------------------------------------------------------------ jobs

    private interface Job {
        /** @return the last per-item error, if any */
        String run(UUID tenantId);
    }

    private JobStatus start(String name, Job job) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (!jobs.claim(tenantId, name, userId)) {
            throw new EncryptionException(HttpStatus.CONFLICT, "ENCRYPTION_JOB_RUNNING",
                    "That job is already running - wait for it to finish.");
        }
        executor.submit(() -> {
            TenantContext.set(tenantId, userId); // the completion audit event names who started it
            ScheduledFuture<?> beat = heartbeats.scheduleWithFixedDelay(() -> jobs.heartbeat(tenantId, name),
                    30, 30, TimeUnit.SECONDS);
            try {
                job.run(tenantId);
            } catch (RuntimeException e) {
                log.error("Encryption job {} for tenant {} failed", name, tenantId, e);
                jobs.finish(tenantId, name, true, e.getMessage());
            } finally {
                beat.cancel(false);
                TenantContext.clear();
            }
        });
        return jobs.get(tenantId, name);
    }

    /**
     * Moves every one of the tenant's data keys onto its latest master key. Same service and scope:
     * re-wrapped inside the key service, in bulk, the data key never exposed. Otherwise (the pre-V57
     * platform key, or another key service): unwrapped by its own service, wrapped by the tenant's key, wiped.
     */
    private String runRewrap(UUID tenantId) {
        String scope = KeyEncryptionService.scopeOf(tenantId);
        KeyEncryptionService primary = keys.primary();
        int latest = primary.info(scope).latestVersion();
        Set<UUID> failedIds = new HashSet<>();
        String lastError = null;
        while (true) {
            List<StorageObjectKey> batch = new ArrayList<>(keyRepository.findToRewrap(tenantId, primary.provider(),
                    scope, latest, PageRequest.of(0, BATCH + failedIds.size())));
            batch.removeIf(row -> failedIds.contains(row.getStorageObjectKeyId()));
            if (batch.isEmpty()) {
                break;
            }
            // Same service and scope: one bulk re-wrap request per hundred keys.
            List<StorageObjectKey> sameKey = batch.stream().filter(row -> row.getKeyProvider().equals(primary.provider())
                    && row.getKeyScope().equals(scope)).toList();
            List<String> rewrappedSame = sameKey.isEmpty() ? List.of()
                    : primary.rewrapAll(scope, sameKey.stream().map(StorageObjectKey::getWrappedKey).toList());
            Map<UUID, String> rewrapped = new LinkedHashMap<>();
            for (int i = 0; i < sameKey.size(); i++) {
                rewrapped.put(sameKey.get(i).getStorageObjectKeyId(), rewrappedSame.get(i));
            }
            int done = 0;
            long failedNow = 0;
            for (StorageObjectKey row : batch) {
                try {
                    String next = rewrapped.containsKey(row.getStorageObjectKeyId())
                            ? rewrapped.get(row.getStorageObjectKeyId()) : moveKey(row, primary, scope);
                    if (next == null) {
                        throw new KeyEncryptionService.KeyServiceException("The key service refused to re-wrap this key");
                    }
                    row.setWrappedKey(next);
                    row.setKeyProvider(primary.provider());
                    row.setKeyScope(scope);
                    row.setKeyVersion(KeyEncryptionService.versionOf(next));
                    row.setRewrappedAt(OffsetDateTime.now());
                    keyRepository.save(row);
                    done++;
                } catch (RuntimeException e) {
                    failedIds.add(row.getStorageObjectKeyId());
                    failedNow++;
                    lastError = e.getMessage();
                }
            }
            jobs.progress(tenantId, REWRAP, done, failedNow, failedNow > 0 ? lastError : null);
            if (done == 0) {
                break; // only rows that keep failing are left
            }
        }
        JobStatus end = jobs.finish(tenantId, REWRAP, false, null);
        audit(AuditActionCatalog.ENCRYPTION_KEYS_REWRAPPED, end.failed() == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                safeKeyName(scope), Map.of("toVersion", latest, "rewrapped", end.processed(), "failed", end.failed()));
        return lastError;
    }

    /** A key from another scope or service: unwrapped by its own service, wrapped with the tenant's key. */
    private String moveKey(StorageObjectKey row, KeyEncryptionService primary, String scope) {
        byte[] dataKey = keys.forProvider(row.getKeyProvider()).unwrap(row.getKeyScope(), row.getWrappedKey());
        try {
            return primary.wrap(scope, dataKey);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /** One kind of file stored before encryption: how to find them, and how to encrypt one. */
    private record Source(String name, String query) {
    }

    // Each query: the tenant's files with no key row, after a (created_at, id) cursor, oldest first.
    private static final String NO_KEY = """
             and not exists (select 1 from storage_object_keys k
                             where k.storage_location_id = %1$s and k.object_key = %2$s)
            """;
    private static final Source VERSIONS = new Source("document", """
            select v.version_id as id, v.storage_location_id as location_id, v.storage_object_key as object_key,
                   v.size_bytes as size_bytes, v.file_hash as file_hash, v.created_at as created_at
            from document_versions v
            where not v.is_deleted and v.tenant_id = ? and (v.created_at, v.version_id) > (?, ?)
            """ + NO_KEY.formatted("v.storage_location_id", "v.storage_object_key")
            + " order by v.created_at, v.version_id limit ?");
    private static final Source QUARANTINE = new Source("quarantined file", """
            select q.quarantine_id as id, q.quarantine_location_id as location_id,
                   q.quarantine_object_key as object_key, q.size_bytes as size_bytes, q.file_hash as file_hash,
                   q.created_at as created_at
            from quarantine_records q
            where not q.is_deleted and q.quarantine_object_key is not null and q.tenant_id = ?
              and (q.created_at, q.quarantine_id) > (?, ?)
            """ + NO_KEY.formatted("q.quarantine_location_id", "q.quarantine_object_key")
            + " order by q.created_at, q.quarantine_id limit ?");
    // Export ZIPs have no recorded hash - the size still has to match.
    private static final Source EXPORTS = new Source("export", """
            select e.export_job_id as id, e.storage_location_id as location_id, e.file_object_key as object_key,
                   e.file_size_bytes as size_bytes, null as file_hash, e.created_at as created_at
            from export_jobs e
            where e.file_object_key is not null and e.storage_location_id is not null
              and e.status = 'completed' and (e.expires_at is null or e.expires_at > now())
              and e.tenant_id = ? and (e.created_at, e.export_job_id) > (?, ?)
            """ + NO_KEY.formatted("e.storage_location_id", "e.file_object_key")
            + " order by e.created_at, e.export_job_id limit ?");

    private static final String UNENCRYPTED_VERSIONS = "select count(*) from document_versions v where not v.is_deleted "
            + "and v.tenant_id = ?" + NO_KEY.formatted("v.storage_location_id", "v.storage_object_key");
    private static final String UNENCRYPTED_QUARANTINE = "select count(*) from quarantine_records q where not q.is_deleted "
            + "and q.quarantine_object_key is not null and q.tenant_id = ?"
            + NO_KEY.formatted("q.quarantine_location_id", "q.quarantine_object_key");
    private static final String UNENCRYPTED_EXPORTS = "select count(*) from export_jobs e where e.file_object_key is not null "
            + "and e.storage_location_id is not null and e.status = 'completed' "
            + "and (e.expires_at is null or e.expires_at > now()) and e.tenant_id = ?"
            + NO_KEY.formatted("e.storage_location_id", "e.file_object_key");

    /** Encrypts the tenant's files stored before encryption - documents, quarantined files, exports. */
    private String runBackfill(UUID tenantId) {
        String lastError = null;
        for (Source source : List.of(VERSIONS, QUARANTINE, EXPORTS)) {
            String error = backfill(tenantId, source);
            if (error != null) {
                lastError = error;
            }
        }
        JobStatus end = jobs.finish(tenantId, ENCRYPT_EXISTING, false, null);
        audit(AuditActionCatalog.ENCRYPTION_BACKFILL_COMPLETED,
                end.failed() == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                safeKeyName(KeyEncryptionService.scopeOf(tenantId)),
                Map.of("encrypted", end.processed(), "failed", end.failed()));
        return lastError;
    }

    private String backfill(UUID tenantId, Source source) {
        Timestamp afterCreated = new Timestamp(0);
        UUID afterId = new UUID(0, 0);
        String lastError = null;
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList(source.query(), tenantId, afterCreated, afterId, BATCH);
            if (batch.isEmpty()) {
                return lastError;
            }
            for (Map<String, Object> file : batch) {
                afterCreated = (Timestamp) file.get("created_at");
                afterId = (UUID) file.get("id");
                try {
                    encryptFile(file, tenantId);
                    jobs.progress(tenantId, ENCRYPT_EXISTING, 1, 0, null);
                } catch (RuntimeException e) {
                    lastError = source.name() + " " + file.get("id") + ": " + e.getMessage();
                    log.warn("Could not encrypt {}", lastError, e);
                    jobs.progress(tenantId, ENCRYPT_EXISTING, 0, 1, lastError);
                }
            }
        }
    }

    private void encryptFile(Map<String, Object> file, UUID tenantId) {
        UUID locationId = (UUID) file.get("location_id");
        StorageLocation location = locations.findById(locationId)
                .orElseThrow(() -> new IllegalStateException("storage location " + locationId + " is gone"));
        Number size = (Number) file.get("size_bytes");
        if (size == null) {
            throw new IllegalStateException("its size isn't recorded");
        }
        // The file's own key stays as it is - the key row points at the encrypted copy.
        encryption.wrap(storage.resolveUnencrypted(location)).encryptExisting(location,
                (String) file.get("object_key"), size.longValue(), (String) file.get("file_hash"), tenantId);
    }

    private long count(String query, UUID tenantId) {
        Long count = jdbc.queryForObject(query, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private void audit(String action, AuditOutcome outcome, String keyName, Map<String, Object> details) {
        auditService.event(action)
                .objectType(AuditObjectTypes.ENCRYPTION_KEY)
                .resourceName(keys.primary().provider() + ":" + keyName)
                .outcome(outcome)
                .newValue(details)
                .log();
    }

    private String safeKeyName(String scope) {
        try {
            return keys.primary().info(scope).keyName();
        } catch (RuntimeException e) {
            return scope;
        }
    }
}
