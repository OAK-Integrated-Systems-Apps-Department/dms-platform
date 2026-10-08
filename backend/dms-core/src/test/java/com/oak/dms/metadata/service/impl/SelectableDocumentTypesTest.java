package com.oak.dms.metadata.service.impl;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.metadata.dto.SelectableDocumentTypeResponse;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.mapper.DocumentTypeMapper;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentTypeServiceImpl#listSelectableDocumentTypes - the upload picker's
 * list: only active types, in display order, for the caller's own tenant,
 * and only id / code / name.
 */
class SelectableDocumentTypesTest {

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void onlyActiveTypes_inDisplayOrder_forTheCallersTenant() {
        TenantContext.set(tenantId, UUID.randomUUID());
        DocumentTypeRepository repository = mock(DocumentTypeRepository.class);
        DocumentType invoice = type("INV", "Invoice", DocumentType.DocumentTypeStatus.active);
        DocumentType retired = type("OLD", "Retired form", DocumentType.DocumentTypeStatus.inactive);
        DocumentType contract = type("CON", "Contract", DocumentType.DocumentTypeStatus.active);
        // The repository already returns display order; the service must keep it.
        when(repository.findAllByTenantOrderedByDisplayOrder(tenantId)).thenReturn(List.of(invoice, retired, contract));
        DocumentTypeServiceImpl service = new DocumentTypeServiceImpl(repository,
                mock(DocumentTypeSchemaRepository.class), mock(DocumentTypeMapper.class), mock(AuditService.class));

        List<SelectableDocumentTypeResponse> result = service.listSelectableDocumentTypes();

        assertThat(result).extracting(SelectableDocumentTypeResponse::typeCode).containsExactly("INV", "CON");
        assertThat(result.get(0).documentTypeId()).isEqualTo(invoice.getDocumentTypeId());
        assertThat(result.get(0).typeName()).isEqualTo("Invoice");
        assertThat(SelectableDocumentTypeResponse.class.getRecordComponents()).extracting("name")
                .containsExactly("documentTypeId", "typeCode", "typeName");
    }

    private DocumentType type(String code, String name, DocumentType.DocumentTypeStatus status) {
        return DocumentType.builder()
                .documentTypeId(UUID.randomUUID())
                .tenantId(tenantId)
                .typeCode(code)
                .typeName(name)
                .status(status)
                .build();
    }
}
