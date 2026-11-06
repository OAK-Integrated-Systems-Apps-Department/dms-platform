package com.oak.dms.preview;

import com.oak.dms.document.FingerprintBackfill;
import com.oak.dms.document.PageCountBackfill;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** TEMPORARY: downloads while the scheduled backfills run, to see if they race. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ScratchBackfillRaceIT extends WorkflowApiSupport {

    @Autowired
    FingerprintBackfill fingerprint;
    @Autowired
    PageCountBackfill pages;

    @Test
    void downloadsWhileBackfillsRun() throws Exception {
        UUID tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        User viewer = fx.user(tenantId, "Vera");
        fx.role(tenantId, "Readers", new String[]{"docm.documents.view"}, viewer);
        List<String> failures = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread background = new Thread(() -> {
            while (!stop.get()) {
                try {
                    fingerprint.run();
                    pages.run();
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add("background: " + t);
                    }
                }
            }
        });
        background.start();
        try {
            for (int i = 0; i < 60; i++) {
                UUID documentId = fx.document(tenantId, type, viewer, "Doc " + i);
                UUID versionId = fx.jdbc().queryForObject(
                        "select current_version_id from documents where document_id = ?", UUID.class, documentId);
                fx.putVersionContent(versionId, pdf());
                MockHttpServletResponse r = getRaw("/api/v1/documents/" + documentId + "/content", fx.jwtFor(viewer));
                if (r.getStatus() != 200) {
                    failures.add("download " + i + " -> " + r.getStatus() + " " + r.getContentAsString());
                }
            }
        } catch (Throwable t) {
            failures.add("test thread: " + t);
        } finally {
            stop.set(true);
            background.join();
        }
        System.out.println("SCRATCH-RESULT failures=" + failures);
        org.assertj.core.api.Assertions.assertThat(failures).isEmpty();
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(out);
            return out.toByteArray();
        }
    }
}
