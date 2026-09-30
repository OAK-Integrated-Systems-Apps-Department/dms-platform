package com.oak.dms.scanning.controller;

import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Where an uploader polls a pending upload (the {@code Location} of a
 * {@code 202} from {@code POST /api/v1/documents}). Always {@code 200} with
 * the ticket; the ticket's {@code status} says whether it is still pending.
 * Only the uploader can see their own ticket - anyone else gets 404.
 * Admin views of scan results and quarantine are Phase 2.
 */
@RestController
@RequestMapping("/api/v1/ingestion-tickets")
@RequiredArgsConstructor
@Tag(name = "Ingestion tickets", description = "Malware-scan status of an upload")
public class IngestionTicketController {

    private final IngestionGate ingestionGate;

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Get the scan status of one of your uploads")
    public IngestionTicketResponse getTicket(@PathVariable("id") UUID id) {
        return ingestionGate.getTicket(id);
    }
}
