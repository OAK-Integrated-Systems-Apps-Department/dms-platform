package com.oak.dms.signature.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.EncryptionProperties;
import com.oak.dms.encryption.OpenBaoTransitKeyService;
import com.oak.dms.signature.service.DocumentSigner;
import com.oak.dms.signature.service.LocalDevDocumentSigner;
import com.oak.dms.signature.service.NullDocumentSigner;
import com.oak.dms.signature.service.TransitDocumentSigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/**
 * Picks the {@link DocumentSigner} and refuses to start with an unsafe setup -
 * mirrors {@code EncryptionConfig} exactly, for the same reason: a
 * development, unmanaged signing key must never be reachable from a
 * deployment that promises managed keys.
 * <p>
 * Exactly one of the two beans below is ever created, selected by
 * {@code dms.signature.enabled} - this (not a class-level
 * {@code @ConditionalOnProperty}) is deliberate: {@code WorkflowTaskServiceImpl}
 * unconditionally depends on {@code DocumentSignatureService}, which
 * unconditionally depends on {@code DocumentSigner}, so a {@code DocumentSigner}
 * bean must always exist or the whole application fails to start whenever
 * this feature is off. {@link NullDocumentSigner} is that always-there
 * fallback - every operation on it refuses with 503
 * {@code SIGNATURE_FEATURE_DISABLED} rather than not existing, which is the
 * one place this feature's "off means 404" convention isn't quite exact
 * (see that class's own doc comment for why).
 */
@Slf4j
@Configuration
public class SignatureConfig {

    @Bean
    @ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
    public DocumentSigner enabledDocumentSigner(SignatureProperties properties,
                                                EncryptionProperties encryptionProperties, ObjectMapper mapper) {
        String provider = properties.getProvider();
        if ("openbao".equals(provider)) {
            OpenBaoTransitKeyService transit = new OpenBaoTransitKeyService(RestClient.builder(), mapper,
                    properties.getOpenbao());
            log.info("Digital signatures ON: ECDSA P-256 keys signed by OpenBao transit {}/{}-<tenant>-<user>-<generation>",
                    properties.getOpenbao().getMount(), properties.getOpenbao().getKeyName());
            return new TransitDocumentSigner(transit);
        }
        if (!"local-dev".equals(provider)) {
            throw new IllegalStateException("Unknown dms.signature.provider '" + provider
                    + "' - use openbao (production) or local-dev (development)");
        }
        if (!properties.isAllowLocalDev()) {
            throw new IllegalStateException("dms.signature.provider is local-dev but dms.signature.allow-local-dev "
                    + "is false - production must use dms.signature.provider=openbao");
        }
        if (encryptionProperties.isRequireManagedKeys()) {
            throw new IllegalStateException("dms.encryption.require-managed-keys is set but dms.signature.provider "
                    + "is local-dev - a deployment that requires managed encryption keys must also sign with "
                    + "managed keys (dms.signature.provider=openbao)");
        }
        log.warn("Digital signatures ON with the DEVELOPMENT signer at {} - not for production",
                properties.getLocalDevKeyFile());
        return new LocalDevDocumentSigner(Path.of(properties.getLocalDevKeyFile()), mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "false", matchIfMissing = true)
    public DocumentSigner disabledDocumentSigner() {
        return new NullDocumentSigner();
    }
}
