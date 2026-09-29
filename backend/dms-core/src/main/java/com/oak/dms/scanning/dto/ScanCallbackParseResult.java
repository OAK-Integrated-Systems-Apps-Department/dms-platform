package com.oak.dms.scanning.dto;

import java.util.List;

/**
 * @param handshakeResponse body to answer with instead of processing events (Azure Event Grid's
 *                          subscription validation handshake); null for a normal delivery
 */
public record ScanCallbackParseResult(
        String handshakeResponse,
        List<ScanCallbackEvent> events
) {

    public static ScanCallbackParseResult events(List<ScanCallbackEvent> events) {
        return new ScanCallbackParseResult(null, events);
    }

    public static ScanCallbackParseResult handshake(String response) {
        return new ScanCallbackParseResult(response, List.of());
    }
}
