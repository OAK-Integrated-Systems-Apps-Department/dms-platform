package com.oak.dms.sharing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShareEventRepository extends JpaRepository<ShareEvent, UUID> {

    Page<ShareEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

    Page<ShareEvent> findByTenantIdAndTargetIdOrderByOccurredAtDesc(UUID tenantId, UUID targetId, Pageable pageable);

    Page<ShareEvent> findByTenantIdAndLinkIdOrderByOccurredAtDesc(UUID tenantId, UUID linkId, Pageable pageable);

    boolean existsByLinkIdAndEventType(UUID linkId, String eventType);
}
