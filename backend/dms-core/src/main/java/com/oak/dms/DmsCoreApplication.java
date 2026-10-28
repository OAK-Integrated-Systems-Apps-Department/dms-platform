package com.oak.dms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} is required for {@code OutboxRelay}'s
 * {@code @Scheduled} method to actually run - without it, Spring treats
 * the annotation as inert and never invokes the method at all. Its
 * absence here was a real, silent gap: every outbox_events row ever
 * written stayed stuck in "pending" (confirmed against the dev database -
 * none had ever reached "published" or "failed"), so no domain event this
 * app has ever emitted had actually been relayed to RabbitMQ.
 * <p>
 * {@code @EnableAsync} used to back {@code DocumentServiceImpl#triggerOcrAsync}
 * -- removed in Phase 1 B.1 along with that method: upload/rerun now queue
 * OCR via a transactional processing_jobs row + outbox event instead of a
 * synchronous HTTP call fired from a background thread.
 */
@SpringBootApplication
@EnableScheduling
public class DmsCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(DmsCoreApplication.class, args);
	}

}
