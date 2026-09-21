package com.oak.dms.audit.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * previous_value / new_value must have sensitive fields redacted before
 * write (docs/audit_events_schema_spec.md). Walks the value as a JSON
 * tree and blanks out any field whose name matches a known-sensitive key,
 * at any nesting depth, rather than trusting each call site to remember
 * to strip these itself.
 */
@Component
public class AuditValueRedactor {

    private static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordhash", "password_hash", "temporarypassword", "temporary_password",
            "secret", "token", "accesstoken", "access_token", "refreshtoken", "refresh_token",
            "otp", "otpcode", "otp_code", "mfasecret", "mfa_secret", "apikey", "api_key"
    );

    private final ObjectMapper objectMapper;

    public AuditValueRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Returns a JSON string with sensitive fields redacted, or null for a null input. */
    public String redactAndSerialize(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode tree = objectMapper.valueToTree(value);
        redactInPlace(tree);
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize audit value after redaction", e);
        }
    }

    private void redactInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> fieldNames = object.fieldNames();
            List<String> toRedact = new ArrayList<>();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (isSensitive(fieldName)) {
                    toRedact.add(fieldName);
                } else {
                    redactInPlace(object.get(fieldName));
                }
            }
            toRedact.forEach(fieldName -> object.set(fieldName, TextNode.valueOf(REDACTED)));
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::redactInPlace);
        }
    }

    private boolean isSensitive(String fieldName) {
        return SENSITIVE_KEYS.contains(fieldName.toLowerCase().replace("_", ""))
                || SENSITIVE_KEYS.contains(fieldName.toLowerCase());
    }
}
