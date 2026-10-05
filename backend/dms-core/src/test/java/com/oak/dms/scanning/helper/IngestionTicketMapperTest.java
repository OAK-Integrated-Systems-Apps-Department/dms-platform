package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanResultStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the uploader is told when a file can't be scanned - why it couldn't, in words. */
class IngestionTicketMapperTest {

    private final IngestionTicketMapper mapper = new IngestionTicketMapper();

    private static ScanResult unscannable(String reason) {
        return ScanResult.builder().scanResultId(UUID.randomUUID()).status(ScanResultStatus.unscannable)
                .originalFilename("archive.zip").failureReason(reason).createdAt(OffsetDateTime.now()).build();
    }

    @Test
    void anArchiveTheScannerCouldNotFinish_saysSo() {
        assertThat(mapper.toTicket(unscannable("Heuristics.Limits.Exceeded.MaxRecursion"), null).message())
                .contains("nested too deeply or holds too much");
    }

    @Test
    void anEncryptedFile_isStillToldToRemoveTheProtection() {
        assertThat(mapper.toTicket(unscannable("Password-protected ZIP archive"), null).message())
                .contains("Password-protected or encrypted");
    }
}
