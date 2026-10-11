package com.oak.dms.importexport;

import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.service.BulkExportService;
import com.oak.dms.importexport.service.BulkImportService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkExportControllerTest {

    private final BulkExportService bulkExportService = mock(BulkExportService.class);
    private final BulkImportService bulkImportService = mock(BulkImportService.class);
    private final BulkExportController controller = new BulkExportController(bulkExportService, bulkImportService);

    @Test
    void listDocumentTypes_returnsTheActiveTypeOptions() {
        List<ImportDocumentTypeOption> options = List.of(
                new ImportDocumentTypeOption(UUID.randomUUID(), "Invoice", "INV"),
                new ImportDocumentTypeOption(UUID.randomUUID(), "Contract", "CON"));
        when(bulkImportService.listDocumentTypes()).thenReturn(options);

        assertThat(controller.listDocumentTypes()).isEqualTo(options);
        verify(bulkImportService).listDocumentTypes();
        verifyNoInteractions(bulkExportService);
    }

    @Test
    void listDocumentTypes_isGatedOnTheExportPermissionsNotTheImportOne() throws Exception {
        var method = BulkExportController.class.getMethod("listDocumentTypes");
        String guard = method.getAnnotation(PreAuthorize.class).value();
        String listMineGuard = BulkExportController.class
                .getMethod("listMine", Pageable.class).getAnnotation(PreAuthorize.class).value();

        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/bulk-exports/document-types");
        assertThat(guard)
                .isEqualTo(listMineGuard)
                .contains("FIE_BULK_EXPORT_EXECUTE")
                .contains("DOCM_DOCUMENTS_VIEW")
                .doesNotContain("FIE_BULK_IMPORT");
    }
}
