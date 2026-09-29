package com.oak.dms.audit.repository;

import com.oak.dms.audit.entity.AuditEvent;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately extends the bare {@link Repository} marker, not
 * {@code JpaRepository}/{@code CrudRepository}: this interface only
 * declares the methods audit_events actually supports. There is no
 * {@code delete}/{@code deleteById}/{@code deleteAll} here at all - not
 * "unused", genuinely absent from the interface - and {@link #save} is
 * only ever called by {@link AuditService} to insert a brand-new row,
 * never to modify an existing one (there is no code path that loads an
 * AuditEvent and calls save() on it again). {@link JpaSpecificationExecutor}
 * is safe to add alongside that - every method it contributes
 * ({@code findAll}/{@code findOne}/{@code count} taking a
 * {@link Specification}) is read-only, backing {@link AuditEventServiceImpl}'s
 * dynamic search filters.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findById(UUID id);

    /**
     * The chain's current tail, for computing the next row's
     * previous_hash. Ordering by occurred_at is a real recency order in
     * practice (writes are serialized - see AuditService's advisory
     * lock); id is only a deterministic tiebreak for the
     * astronomically unlikely case of two rows sharing a timestamp, not
     * a meaningful ordering on its own.
     */
    Optional<AuditEvent> findTopByOrderByOccurredAtDescIdDesc();
}
