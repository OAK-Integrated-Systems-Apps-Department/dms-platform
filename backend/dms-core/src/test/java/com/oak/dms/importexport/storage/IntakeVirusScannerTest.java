package com.oak.dms.importexport.storage;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.exception.ImportFileBlockedException;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanVerdictStatus;
import com.oak.dms.scanning.exception.ScanEngineUnavailableException;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.scanning.service.ScanService;
import com.oak.dms.storage.entity.StorageLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bulk import files are virus-scanned as they arrive; a threat stops the upload there. */
class IntakeVirusScannerTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final ScanEngineResolver resolver = mock(ScanEngineResolver.class);
    private final ScanEngineFactory factory = mock(ScanEngineFactory.class);
    private final AuditService audit = mock(AuditService.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final ScanService scanner = mock(ScanService.class);
    private final ScanEngine engine = mock(ScanEngine.class);
    private final StorageLocation staging = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
    private final com.oak.dms.importexport.repository.ImportItemRepository items =
            mock(com.oak.dms.importexport.repository.ImportItemRepository.class);
    private final UUID itemId = UUID.randomUUID();
    private IntakeVirusScanner intake;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(resolver.resolveForTenant(TENANT)).thenReturn(engine);
        when(factory.resolveCompatible(engine, staging)).thenReturn(scanner);
        when(scanner.scanMode()).thenReturn(ScanMode.pre_store_stream);
        intake = new IntakeVirusScanner(resolver, factory, audit, tx, items);
    }

    @Test
    void infected_isRefused_andAuditedAsBlocked() {
        when(scanner.scan(any(), any())).thenReturn(verdict(ScanVerdictStatus.INFECTED, "Eicar-Test-Signature"));

        assertThatThrownBy(() -> scan())
                .isInstanceOf(ImportFileBlockedException.class)
                .hasMessageContaining("Eicar-Test-Signature")
                .hasMessageContaining("has not been stored");
        verify(audit).event("IMPORT_FILE_BLOCKED");
    }

    @Test
    void infected_keepsTheReasonOnTheRow_soItsResultsSayAVirusWasFound() {
        com.oak.dms.importexport.entity.ImportItem row = com.oak.dms.importexport.entity.ImportItem.builder()
                .importItemId(itemId).build();
        when(items.findById(itemId)).thenReturn(java.util.Optional.of(row));
        when(scanner.scan(any(), any())).thenReturn(verdict(ScanVerdictStatus.INFECTED, "Eicar-Test-Signature"));

        assertThatThrownBy(() -> scan()).isInstanceOf(ImportFileBlockedException.class);

        org.assertj.core.api.Assertions.assertThat(row.getIntakeBlockedReason())
                .contains("blocked by the virus scan (Eicar-Test-Signature)");
        verify(items).save(row);
    }

    @Test
    void unscannable_isRefused() {
        when(scanner.scan(any(), any())).thenReturn(verdict(ScanVerdictStatus.UNSCANNABLE, "Heuristics.Encrypted.PDF"));

        assertThatThrownBy(() -> scan()).isInstanceOf(ImportFileBlockedException.class).hasMessageContaining("password");
    }

    @Test
    void clean_cloudEngine_orEngineDown_letTheFileThrough() {
        when(scanner.scan(any(), any())).thenReturn(verdict(ScanVerdictStatus.CLEAN, null));
        assertThatCode(this::scan).doesNotThrowAnyException();

        when(scanner.scan(any(), any())).thenThrow(new ScanEngineUnavailableException("clamd down", null));
        assertThatCode(this::scan).doesNotThrowAnyException(); // scanned again at the gate

        when(scanner.scanMode()).thenReturn(ScanMode.post_store_object);
        assertThatCode(this::scan).doesNotThrowAnyException();
        verify(audit, never()).event(anyString());
    }

    private void scan() {
        intake.scan(TENANT, UUID.randomUUID(), itemId, "a.pdf", staging, "imports/k", 10, "application/pdf");
    }

    private static ScanVerdict verdict(ScanVerdictStatus status, String threat) {
        return new ScanVerdict(status, threat, null, "ClamAV", "1.4", "27000", OffsetDateTime.now(), null);
    }
}
