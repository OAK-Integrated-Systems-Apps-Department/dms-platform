package com.oak.dms.scanning.controller;

import com.oak.dms.scanning.dto.IngestionTicketResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/**
 * The one mapping from an ingestion ticket to an HTTP response, shared by
 * every upload endpoint so the contract stays uniform:
 * <ul>
 *   <li>{@code clean} - {@code 201 Created}, {@code Location: /api/v1/documents/{documentId}};</li>
 *   <li>{@code pending} - {@code 202 Accepted}, {@code Location: /api/v1/ingestion-tickets/{id}},
 *   {@code Retry-After} - the caller polls the ticket until it is terminal;</li>
 *   <li>{@code infected}/{@code quarantined}/{@code unscannable}/{@code failed} -
 *   {@code 422 Unprocessable Content}, ticket body explains why.</li>
 * </ul>
 */
public final class IngestionTicketResponses {

    static final String TICKETS_PATH = "/api/v1/ingestion-tickets/";
    private static final String RETRY_AFTER_SECONDS = "5";

    private IngestionTicketResponses() {
    }

    public static ResponseEntity<IngestionTicketResponse> toResponseEntity(IngestionTicketResponse ticket) {
        return switch (ticket.status()) {
            case clean -> ticket.documentId() != null
                    ? ResponseEntity.created(URI.create("/api/v1/documents/" + ticket.documentId())).body(ticket)
                    : ResponseEntity.status(HttpStatus.CREATED).body(ticket);
            case pending -> ResponseEntity.accepted()
                    .location(URI.create(TICKETS_PATH + ticket.scanResultId()))
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(ticket);
            case infected, quarantined, unscannable, failed ->
                    ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ticket);
        };
    }
}
