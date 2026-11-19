package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.PromotionRefusedException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-06: how a promotion refusal is stored on the scan result and shown on the ingestion ticket. */
class PromotionRefusalTicketTest {

    private final IngestionTicketMapper mapper = new IngestionTicketMapper();

    private ScanResult failed(String reason) {
        return ScanResult.builder().scanResultId(UUID.randomUUID()).status(ScanResultStatus.failed)
                .failureReason(reason).originalFilename("x.pdf").build();
    }

    @Test
    void aRefusal_isStoredWithItsCode_andTheUploaderIsToldWhy() {
        PromotionRefusedException refusal = new PromotionRefusedException("DOCUMENT_LEGAL_HOLD",
                "\"contract.pdf\" was placed under legal hold while the file was being scanned: not added.");

        assertThat(refusal.toFailureReason())
                .startsWith("promotion-refused:DOCUMENT_LEGAL_HOLD:")
                .endsWith("not added.");
        assertThat(PromotionRefusedException.userMessage(refusal.toFailureReason()))
                .contains("\"contract.pdf\" was placed under legal hold while the file was being scanned: not added.");

        IngestionTicketResponse ticket = mapper.toTicket(failed(refusal.toFailureReason()), null);
        assertThat(ticket.status()).isEqualTo(ScanResultStatus.failed);
        assertThat(ticket.message()).startsWith("The file passed the malware scan but was not added: ")
                .contains("legal hold")
                .doesNotContain("could not be scanned");
        assertThat(ticket.retrying()).isFalse();
    }

    @Test
    void aMessageThatItselfContainsColons_isShownWhole() {
        PromotionRefusedException refusal = new PromotionRefusedException("DOCUMENT_HAS_SIGNATURE", "a: b: c");
        assertThat(PromotionRefusedException.userMessage(refusal.toFailureReason())).contains("a: b: c");
    }

    @Test
    void anOrdinaryFailure_keepsItsGenericMessage() {
        for (String reason : new String[]{null, "Staging upload failed: disk full", "Unexpected error: NullPointerException"}) {
            IngestionTicketResponse ticket = mapper.toTicket(failed(reason), null);
            assertThat(ticket.message()).as(String.valueOf(reason))
                    .isEqualTo("The file could not be scanned and was not added. Please try uploading it again later.");
            assertThat(PromotionRefusedException.userMessage(reason)).isEmpty();
        }
    }
}
