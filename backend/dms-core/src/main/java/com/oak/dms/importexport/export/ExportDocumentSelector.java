package com.oak.dms.importexport.export;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.importexport.dto.CreateExportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which documents an export covers - always only the tenant's own,
 * non-deleted documents. Chosen ids keep the order they were given in (and
 * ids from another tenant are silently dropped, never revealed); a filter
 * returns the type's documents oldest first. Used for the count shown
 * before an export starts and again by the worker, so a document deleted
 * in between is simply left out.
 */
@Component
@RequiredArgsConstructor
public class ExportDocumentSelector {

    private static final OffsetDateTime BEGINNING = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final DocumentRepository documentRepository;

    /** @param limit at most this many ids come back - callers pass their maximum + 1 to detect "too many" */
    @Transactional(readOnly = true)
    public List<UUID> select(UUID tenantId, CreateExportRequest request, int limit) {
        if (request.byIds()) {
            Set<UUID> wanted = new LinkedHashSet<>(request.documentIds());
            Map<UUID, Document> found = documentRepository
                    .findAllByDocumentIdInAndTenantIdAndDeletedFalse(wanted, tenantId).stream()
                    .collect(Collectors.toMap(Document::getDocumentId, Function.identity()));
            return wanted.stream().filter(found::containsKey).limit(limit).toList();
        }
        return documentRepository.findIdsByTypeCreatedBetween(tenantId, request.documentTypeId(),
                from(request.createdFrom()), to(request.createdTo()), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public long count(UUID tenantId, CreateExportRequest request) {
        if (request.byIds()) {
            return documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(
                    new LinkedHashSet<>(request.documentIds()), tenantId).size();
        }
        return documentRepository.countByTypeCreatedBetween(tenantId, request.documentTypeId(),
                from(request.createdFrom()), to(request.createdTo()));
    }

    private static OffsetDateTime from(LocalDate date) {
        return date == null ? BEGINNING : date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Inclusive: documents created any time on {@code date} count. */
    private static OffsetDateTime to(LocalDate date) {
        return date == null ? END : date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
