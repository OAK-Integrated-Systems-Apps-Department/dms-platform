package com.oak.dms.scanning.repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Claim/lease queries for {@code ScanRetryScheduler} - see {@link ScanResultRepositoryImpl}. */
public interface ScanResultRepositoryCustom {

    /**
     * Atomically leases up to {@code batchSize} due rows ({@code pending} or
     * {@code infected}, {@code next_attempt_at <= now()}, no live lease) and
     * returns their ids. {@code FOR UPDATE SKIP LOCKED}, so concurrent
     * instances never claim the same row. Each lease is {@code lease} plus
     * {@code leasePerGb} for every whole GB of the row's file.
     */
    List<UUID> claimDueWork(int batchSize, Duration lease, Duration leasePerGb);

    /** Leases one specific row if it is workable and unleased - used by the callback path. */
    boolean claimOne(UUID scanResultId, Duration lease, Duration leasePerGb);

    /** Terminal rows whose staged object hasn't been deleted yet (failed rows only once older than {@code failedRetention}). */
    List<UUID> findStagingToPurge(Duration failedRetention, int batchSize);
}
