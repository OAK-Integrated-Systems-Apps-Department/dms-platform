package com.oak.dms.audit;

import com.oak.dms.audit.dto.AuditEventResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link AuditEvent}. previousValue/newValue
 * and userAgent have no corresponding {@link AuditEventResponse} field and
 * are simply dropped - see that record's doc comment for why.
 */
@Mapper(componentModel = "spring")
public interface AuditEventMapper {
    AuditEventResponse toResponse(AuditEvent event);
}
