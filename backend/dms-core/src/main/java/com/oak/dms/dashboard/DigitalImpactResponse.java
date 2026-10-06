package com.oak.dms.dashboard;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The dashboard's Digital Impact for a period: pages processed digitally (counted from the
 * files), the paper they stand for and that paper's estimated footprint, where those pages came
 * from and which documents held the most, and exactly how it was worked out. Every figure covers
 * only documents the caller can see.
 *
 * @param allTime  every page counted so far, whenever it was added
 * @param coverage how much of the period's uploads have a page count - so the figures can be read honestly
 * @param sources  the period's pages by kind of file, most pages first
 * @param topDocuments the documents whose uploads in the period held the most pages
 * @param method   the assumptions and factors used, for "How is this calculated?"
 */
public record DigitalImpactResponse(
        LocalDate from,
        LocalDate to,
        int days,
        Totals totals,
        Totals previous,
        Totals allTime,
        Coverage coverage,
        List<PageSource> sources,
        List<TopDocument> topDocuments,
        List<Day> daily,
        Method method
) {

    /**
     * @param pages    measured: pages of the versions uploaded in the period that have a page count
     * @param sheets   calculated: the sheets those pages would take, printed {@code pagesPerSheet} to a sheet
     * @param paperKg  calculated: the mass of that paper
     * @param co2eKg   estimated: that paper's life-cycle footprint, from the configured factor
     * @param waterLitres estimated: that paper's water footprint, from the configured factor
     */
    public record Totals(long pages, double sheets, double paperKg, double co2eKg, double waterLitres) {
    }

    /**
     * Versions uploaded in the period: {@code counted} have pages; {@code noPages} are formats
     * without pages (or unreadable); {@code pending} haven't been looked at yet.
     */
    /**
     * @param pending   waiting for the page-count job (a few minutes)
     * @param unscanned never virus-scanned (uploaded before scanning existed, V39), so their content
     *                  can't be read - and won't be counted - until they're rescanned
     */
    public record Coverage(long versions, long counted, long countedFromFile, long reportedByFile,
                           long noPages, long pending, long unscanned) {
    }

    /** Pages of one kind of file: pdf, word, slides (presentations) or scans (TIFF). */
    public record PageSource(String kind, long pages, long files) {
    }

    /** A document and the pages its uploads in the period held. */
    public record TopDocument(UUID documentId, String title, String documentTypeName, String fileName,
                              Long sizeBytes, String mimeType, OffsetDateTime createdAt, long pages) {
    }

    public record Day(LocalDate date, long pages, double paperKg, double co2eKg) {
    }

    public record Method(String calculationVersion, String paperSize, double paperWidthMm, double paperHeightMm,
                         double paperGsm, int pagesPerSheet, double gramsPerPage, double co2eKgPerKgPaper,
                         String co2eSource, double waterLitresPerKgPaper, String waterSource) {
    }
}
