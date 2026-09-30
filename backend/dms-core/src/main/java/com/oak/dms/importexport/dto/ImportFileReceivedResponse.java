package com.oak.dms.importexport.dto;

public record ImportFileReceivedResponse(
        int rowNumber,
        int filesReceived,
        int totalRecords
) {
}
