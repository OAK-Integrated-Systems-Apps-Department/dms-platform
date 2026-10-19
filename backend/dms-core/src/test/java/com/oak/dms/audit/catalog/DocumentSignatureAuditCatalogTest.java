package com.oak.dms.audit.catalog;

import com.oak.dms.audit.enums.AuditSeverity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every 1.4 Digital Signature Integration action is registered with the module, severity and reason rule the feature's design calls for. */
class DocumentSignatureAuditCatalogTest {

    private record Expected(String module, AuditSeverity severity, boolean reasonRequired) {
    }

    @Test
    void signatureActionsAreRegistered() {
        Map<String, Expected> expected = Map.ofEntries(
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNED, new Expected("DSI", AuditSeverity.HIGH, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_REJECTED, new Expected("DSI", AuditSeverity.HIGH, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_INVALIDATED, new Expected("DSI", AuditSeverity.HIGH, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_VERIFIED, new Expected("DSI", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_VERIFICATION_FAILED, new Expected("DSI", AuditSeverity.HIGH, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_LOCKED, new Expected("DSI", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_SIGNATURE_UNLOCKED, new Expected("DSI", AuditSeverity.HIGH, true)),
                Map.entry(AuditActionCatalog.SIGNING_KEY_CREATED, new Expected("DSI", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.SIGNING_KEY_REVOKED, new Expected("DSI", AuditSeverity.HIGH, true)));
        expected.forEach((action, e) -> {
            AuditActionSpec spec = AuditActionCatalog.require(action);
            assertThat(spec.module()).as(action).isEqualTo(e.module());
            assertThat(spec.severity()).as(action).isEqualTo(e.severity());
            assertThat(spec.reasonRequired()).as(action).isEqualTo(e.reasonRequired());
        });
    }
}
