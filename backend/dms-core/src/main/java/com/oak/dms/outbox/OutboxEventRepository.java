package com.oak.dms.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link OutboxEvent}. findBatchToPublish backs
 * {@link OutboxRelay}'s polling loop; the JPQL enum comparison
 * (status = ...OutboxEvent.Status.pending) is written fully qualified
 * because Hibernate resolves enum literals in JPQL by type, not by import.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("select e from OutboxEvent e where e.status = com.oak.dms.outbox.OutboxEvent.Status.pending "
            + "order by e.createdAt asc")
    List<OutboxEvent> findBatchToPublish(org.springframework.data.domain.Pageable pageable);

    @Query("select count(e) from OutboxEvent e where e.status = com.oak.dms.outbox.OutboxEvent.Status.failed "
            + "and e.attempts >= :maxAttempts")
    long countDeadLettered(@Param("maxAttempts") int maxAttempts);
}
