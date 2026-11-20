package com.oak.dms.reporting.dto;

/**
 * One run of a schedule with what happened to its deliveries - counts only, no addresses.
 *
 * @param delivered   null when it was not a delivered run; false with {@code deliveryNote} EMPTY when an empty report was not sent
 */
public record ScheduleRunResponse(RunResponse run, Boolean delivered, String deliveryNote, int sent, int failed, int pending,
                                  int skipped) {
}
