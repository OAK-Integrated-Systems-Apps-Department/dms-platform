package com.oak.dms.document.dto;

/**
 * What OCR reads in a region the reviewer selected. Nothing is stored:
 * this is an answer to "what does that say", and only the reviewer's
 * decision about it is persisted (as a correction, Phase 4d).
 */
public record RubberBandResponse(
        int page,
        double[] bbox,
        String text,
        Double confidence) {
}
