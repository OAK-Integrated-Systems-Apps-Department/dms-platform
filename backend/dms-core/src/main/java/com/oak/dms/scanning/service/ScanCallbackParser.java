package com.oak.dms.scanning.service;

import com.oak.dms.scanning.dto.ScanCallbackParseResult;
import com.oak.dms.scanning.enums.ScanProvider;

/**
 * Turns a cloud provider's verdict notification (Event Grid for Defender,
 * EventBridge for GuardDuty) into provider-independent
 * {@link com.oak.dms.scanning.dto.ScanCallbackEvent}s. Implemented by the
 * cloud adapters, collected by provider the same way {@link ScanEngineFactory}
 * collects {@link ScanService}s. Parsing only - a callback never sets a verdict.
 */
public interface ScanCallbackParser {

    ScanProvider supportedProvider();

    /** Shared secret the provider must send; blank means the callback endpoint is disabled. */
    String callbackSecret();

    ScanCallbackParseResult parse(String body);
}
