package com.oak.dms.importexport.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFilenamesTest {

    @Test
    void strip_dropsFolderPathsWithEitherSeparatorAndWhitespace() {
        assertThat(ImportFilenames.strip("contract.pdf")).isEqualTo("contract.pdf");
        assertThat(ImportFilenames.strip("scans/2026/contract.pdf")).isEqualTo("contract.pdf");
        assertThat(ImportFilenames.strip("C:\\Users\\jane\\contract.pdf")).isEqualTo("contract.pdf");
        assertThat(ImportFilenames.strip("  mixed\\dir/ contract.pdf ")).isEqualTo("contract.pdf");
    }

    @Test
    void strip_nothingLeft_isEmpty() {
        assertThat(ImportFilenames.strip(null)).isEmpty();
        assertThat(ImportFilenames.strip("folder/")).isEmpty();
        assertThat(ImportFilenames.strip("   ")).isEmpty();
    }

    @Test
    void titleFromFilename_dropsPathAndExtension() {
        assertThat(ImportTitles.fromFilename("scans/Q3 report.final.pdf")).isEqualTo("Q3 report.final");
        assertThat(ImportTitles.fromFilename(".env")).isEqualTo(".env");
        assertThat(ImportTitles.fromFilename(" ")).isNull();
    }
}
