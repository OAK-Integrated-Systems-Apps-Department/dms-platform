package com.oak.dms.importexport.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZipEntryNamesTest {

    private final ZipEntryNames names = new ZipEntryNames();

    @Test
    void next_repeatedName_isNumberedBeforeTheExtension() {
        assertThat(names.next("report.pdf")).isEqualTo("report.pdf");
        assertThat(names.next("report.pdf")).isEqualTo("report (2).pdf");
        assertThat(names.next("report.pdf")).isEqualTo("report (3).pdf");
    }

    @Test
    void next_namesDifferingOnlyInCase_areTreatedAsTheSame() {
        assertThat(names.next("Report.PDF")).isEqualTo("Report.PDF");
        assertThat(names.next("report.pdf")).isEqualTo("report (2).pdf");
    }

    @Test
    void next_noExtension_isNumberedAtTheEnd() {
        assertThat(names.next("README")).isEqualTo("README");
        assertThat(names.next("README")).isEqualTo("README (2)");
    }

    @Test
    void next_aNumberedNameAlreadyTaken_isSkipped() {
        names.next("a.txt");
        names.next("a (2).txt");
        assertThat(names.next("a.txt")).isEqualTo("a (3).txt");
    }

    @Test
    void reserve_keepsTheMetadataFileNameFromAnyDocument() {
        names.reserve("metadata.xlsx");
        assertThat(names.next("metadata.xlsx")).isEqualTo("metadata (2).xlsx");
    }

    @Test
    void sanitise_pathsControlCharactersAndDots_areRemoved() {
        assertThat(ZipEntryNames.sanitise("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ZipEntryNames.sanitise("C:\\Users\\x\\scan.tif")).isEqualTo("scan.tif");
        assertThat(ZipEntryNames.sanitise("in\u0000voice\n.pdf")).isEqualTo("invoice.pdf");
        assertThat(ZipEntryNames.sanitise("..hidden")).isEqualTo("hidden");
        assertThat(ZipEntryNames.sanitise("ලංකාව.docx")).isEqualTo("ලංකාව.docx");
    }

    @Test
    void sanitise_nothingLeft_fallsBackToAPlainName() {
        assertThat(ZipEntryNames.sanitise(null)).isEqualTo(ZipEntryNames.FALLBACK_NAME);
        assertThat(ZipEntryNames.sanitise("  ")).isEqualTo(ZipEntryNames.FALLBACK_NAME);
        assertThat(ZipEntryNames.sanitise("folder/")).isEqualTo(ZipEntryNames.FALLBACK_NAME);
    }

    @Test
    void sanitise_veryLongName_isShortenedKeepingTheExtension() {
        String name = ZipEntryNames.sanitise("x".repeat(300) + ".pdf");
        assertThat(name).hasSize(ZipEntryNames.MAX_LENGTH).endsWith(".pdf");
    }
}
