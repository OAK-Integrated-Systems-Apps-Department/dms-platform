package com.oak.dms.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateFilenameTest {

    @Test
    void theNewFileIsNamedAfterTheDocument_andKeepsTheTemplatesExtension() {
        assertThat(DocumentTemplateService.filenameFor("Client agreement - ACME", "Service Agreement Template.docx"))
                .isEqualTo("Client agreement - ACME.docx");
        assertThat(DocumentTemplateService.filenameFor("Q3 budget", "budget-template.XLSX")).isEqualTo("Q3 budget.XLSX");
    }

    @Test
    void charactersAFilenameCannotHold_areReplaced_andLengthIsCapped() {
        assertThat(DocumentTemplateService.filenameFor("Plan: A/B <draft>?", "t.docx")).isEqualTo("Plan A B draft.docx");
        assertThat(DocumentTemplateService.filenameFor("x".repeat(300), "t.docx")).hasSize(120 + ".docx".length());
    }

    @Test
    void aTemplateWithNoExtension_givesAFileWithNone_andAnUnusableTitleKeepsTheTemplatesName() {
        assertThat(DocumentTemplateService.filenameFor("Notes", "template")).isEqualTo("Notes");
        assertThat(DocumentTemplateService.filenameFor("???", "Original.docx")).isEqualTo("Original.docx");
    }

    @Test
    void aPathInTheTemplatesFilename_isIgnored() {
        assertThat(DocumentTemplateService.filenameFor("Memo", "C:\\temp\\old.dir\\tpl.odt")).isEqualTo("Memo.odt");
    }
}
