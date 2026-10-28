package com.oak.dms.relationship.seed;

import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Gives a tenant the system relationship types. Idempotent - a code the tenant already has is left alone. */
@Service
@RequiredArgsConstructor
public class RelationshipTypeSeeder {

    private final RelationshipTypeRepository types;

    @Transactional
    public int install(UUID tenantId) {
        int added = 0;
        for (RelationshipTypeCatalog.Seed seed : RelationshipTypeCatalog.SYSTEM_TYPES) {
            if (types.existsByTenantIdAndCodeAndDeletedFalse(tenantId, seed.code())) {
                continue;
            }
            types.save(RelationshipType.builder()
                    .tenantId(tenantId)
                    .code(seed.code())
                    .category(seed.category())
                    .label(seed.label())
                    .inverseLabel(seed.inverseLabel())
                    .directionality(seed.directionality())
                    .preventsCycles(seed.preventsCycles())
                    .system(true)
                    .enabled(true)
                    .displayOrder(seed.displayOrder())
                    .build());
            added++;
        }
        return added;
    }
}
