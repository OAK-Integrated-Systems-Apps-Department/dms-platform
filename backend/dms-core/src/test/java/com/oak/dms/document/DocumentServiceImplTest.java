package com.oak.dms.document;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentServiceImplTest {

    @Test
    void downloadDocument_runsInAWritableTransactionSoTheAuditInsertIsSaved() throws NoSuchMethodException {
        // A readOnly transaction uses MANUAL flush mode, so the joined
        // DOCUMENT_DOWNLOADED audit insert would be silently discarded.
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        TransactionAttribute download = source.getTransactionAttribute(
                DocumentServiceImpl.class.getMethod("downloadDocument", UUID.class), DocumentServiceImpl.class);
        assertThat(download).isNotNull();
        assertThat(download.isReadOnly()).isFalse();
    }
}
