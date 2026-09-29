package com.oak.dms.scanning.controller;

import com.oak.dms.scanning.dto.ScanCallbackParseResult;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.exception.ScanCallbackAuthenticationException;
import com.oak.dms.scanning.service.ScanCallbackParser;
import com.oak.dms.scanning.service.impl.ScanCallbackHandler;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook endpoints the cloud scanners notify when a verdict is ready:
 * Azure Event Grid (Defender for Storage) and an AWS EventBridge API
 * destination (GuardDuty Malware Protection). Public in
 * {@code SecurityConfig} - neither provider can present a Keycloak JWT - and
 * authenticated instead by a shared secret in {@code X-Oak-Scan-Callback-Secret},
 * compared in constant time. A blank configured secret disables the
 * endpoint entirely (401); verdicts then arrive by polling only.
 * <p>
 * Authentication aside, the endpoints are low-trust by design: a callback
 * only makes a scan result due for re-evaluation, and the verdict is always
 * re-read from the provider itself - see {@link ScanCallbackHandler}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/scanning/callbacks")
public class ScanCallbackController {

    static final String SECRET_HEADER = "X-Oak-Scan-Callback-Secret";
    private static final Map<String, ScanProvider> PROVIDERS_BY_SLUG = Map.of(
            "azure-defender", ScanProvider.AZURE_DEFENDER,
            "aws-guardduty", ScanProvider.AWS_GUARDDUTY);

    private final Map<ScanProvider, ScanCallbackParser> parsersByProvider;
    private final ScanCallbackHandler scanCallbackHandler;

    public ScanCallbackController(List<ScanCallbackParser> parsers, ScanCallbackHandler scanCallbackHandler) {
        this.parsersByProvider = parsers.stream()
                .collect(Collectors.toMap(ScanCallbackParser::supportedProvider, Function.identity()));
        this.scanCallbackHandler = scanCallbackHandler;
    }

    @PostMapping(value = "/{provider}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(@PathVariable("provider") String providerSlug,
                                          @RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                          @RequestBody(required = false) String body) {
        ScanProvider provider = PROVIDERS_BY_SLUG.get(providerSlug);
        ScanCallbackParser parser = provider == null ? null : parsersByProvider.get(provider);
        if (parser == null || !authenticated(parser.callbackSecret(), secret)) {
            throw new ScanCallbackAuthenticationException();
        }

        ScanCallbackParseResult parsed = parser.parse(body == null ? "" : body);
        if (parsed.handshakeResponse() != null) {
            return ResponseEntity.ok(parsed.handshakeResponse());
        }
        int matched = scanCallbackHandler.handleAll(provider, parsed.events());
        return ResponseEntity.ok("{\"received\":" + parsed.events().size() + ",\"matched\":" + matched + "}");
    }

    private static boolean authenticated(String configured, String presented) {
        if (configured == null || configured.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
