package com.oak.dms.watermark.job;

import java.util.UUID;

/**
 * Hands a committed job to whatever is going to produce it.
 * <p>
 * An interface rather than a direct dependency on the worker so the request
 * side - {@code WatermarkService} - compiles and is testable without the
 * executor, the format services and the storage layer behind it. It is also
 * what lets {@link WatermarkJobStarter} do the right thing when no
 * implementation exists at all, which is the case while
 * {@code dms.watermark.enabled} is false.
 */
public interface WatermarkJobQueue {

    /**
     * Queues the job and returns at once. Called only after the job row is
     * committed - see {@link WatermarkJobStarter}.
     */
    void start(UUID jobId);
}
