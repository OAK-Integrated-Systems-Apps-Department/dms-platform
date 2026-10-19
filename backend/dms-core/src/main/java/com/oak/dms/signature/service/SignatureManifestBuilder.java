package com.oak.dms.signature.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.signature.enums.SignatureMeaning;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds the deterministic (sorted-key) JSON text a signature signs -
 * decision 3. The returned text is stored verbatim on the
 * {@code document_signatures} row and is what every later verification
 * replays byte for byte; nothing ever re-serializes a manifest to compare
 * against a stored one, so this class's own stability across versions only
 * matters at the moment a signature is created, not when one is checked.
 * <p>
 * {@code TreeMap} gives natural alphabetical key order, and Jackson
 * preserves a {@code Map}'s own iteration order rather than reordering it,
 * so the written JSON's key order is exactly this class's insertion order
 * into each map (which is also alphabetical, for a human comparing it by eye).
 */
@Component
@RequiredArgsConstructor
public class SignatureManifestBuilder {

    /** Bumped only if the manifest's shape itself ever changes - already-signed manifests are never rewritten. */
    static final String FORMAT = "dms-signature-manifest-v1";

    private final ObjectMapper mapper;

    public record ManifestInput(
            UUID tenantId,
            UUID documentId,
            UUID versionId,
            int versionNumber,
            String fileSha256,
            UUID signerUserId,
            String signerName,
            String signerEmail,
            SignatureMeaning meaning,
            String comment,
            UUID workflowInstanceId,
            UUID workflowTaskId,
            OffsetDateTime signedAt
    ) {
    }

    public String build(ManifestInput input) {
        Map<String, Object> signer = new TreeMap<>();
        signer.put("email", input.signerEmail());
        signer.put("id", input.signerUserId().toString());
        signer.put("name", input.signerName());

        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("comment", input.comment());
        manifest.put("documentId", input.documentId().toString());
        manifest.put("fileSha256", input.fileSha256());
        manifest.put("format", FORMAT);
        manifest.put("meaning", input.meaning().name());
        manifest.put("signedAt", input.signedAt().withOffsetSameInstant(ZoneOffset.UTC).toString());
        manifest.put("signer", signer);
        manifest.put("tenantId", input.tenantId().toString());
        manifest.put("versionId", input.versionId().toString());
        manifest.put("versionNumber", input.versionNumber());
        manifest.put("workflowInstanceId", input.workflowInstanceId().toString());
        manifest.put("workflowTaskId", input.workflowTaskId() == null ? null : input.workflowTaskId().toString());

        try {
            return mapper.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build the signature manifest", e);
        }
    }
}
