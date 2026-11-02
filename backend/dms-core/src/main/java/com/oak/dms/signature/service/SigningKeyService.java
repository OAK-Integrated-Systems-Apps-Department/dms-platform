package com.oak.dms.signature.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.signature.entity.UserSigningKey;
import com.oak.dms.signature.enums.SigningBackend;
import com.oak.dms.signature.repository.UserSigningKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One ECDSA key per user, created lazily on first signing (decision 4).
 * {@link #revoke} never touches any {@code document_signatures} row a
 * revoked key already produced - those stay valid for the version they
 * signed (decision 9); it only stops the key from signing anything new.
 */
@Service
@RequiredArgsConstructor
public class SigningKeyService {

    private final UserSigningKeyRepository repository;
    private final DocumentSigner signer;
    private final AuditService auditService;

    /**
     * Whether this user has ever held a signing key, active or revoked -
     * {@code UserServiceImpl#purgeUser}'s guard: {@code user_signing_keys.user_id}
     * (and, transitively, {@code document_signatures.signer_user_id}) is
     * deliberately RESTRICT, not CASCADE/SET NULL, so a hard delete of a user
     * who has ever signed anything fails at the database - this lets that
     * failure surface as a clear message instead of a raw constraint violation.
     */
    @Transactional(readOnly = true)
    public boolean hasEverSigned(UUID tenantId, UUID userId) {
        return repository.findFirstByTenantIdAndUserIdOrderByGenerationDesc(tenantId, userId).isPresent();
    }

    /** The user's active key, creating one (next generation) if they have none. */
    @Transactional
    public UserSigningKey ensureActiveKey(UUID tenantId, UUID userId) {
        return repository.findByTenantIdAndUserIdAndStatus(tenantId, userId, UserSigningKey.Status.active)
                .orElseGet(() -> createKey(tenantId, userId));
    }

    private UserSigningKey createKey(UUID tenantId, UUID userId) {
        int generation = repository.findFirstByTenantIdAndUserIdOrderByGenerationDesc(tenantId, userId)
                .map(k -> k.getGeneration() + 1).orElse(1);
        String keyName = "dms-sig-" + tenantId + "-" + userId + "-" + generation;
        String publicKeyPem = signer.ensureKey(keyName);

        UserSigningKey key = UserSigningKey.builder()
                .keyId(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .generation(generation)
                .keyName(keyName)
                .publicKeyPem(publicKeyPem)
                .backend(signer.backend().wireValue())
                .status(UserSigningKey.Status.active)
                .createdBy(userId)
                .build();
        UserSigningKey saved = repository.save(key);

        auditService.event(AuditActionCatalog.SIGNING_KEY_CREATED)
                .objectType(AuditObjectTypes.SIGNING_KEY)
                .objectId(saved.getKeyId())
                .resourceName(keyName)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("userId", userId, "generation", generation, "backend", saved.getBackend()))
                .log();
        return saved;
    }

    /** No-op (not an error) if the user has no active key - deactivating/deleting a user who never signed. */
    @Transactional
    public void revokeActiveKeyIfAny(UUID tenantId, UUID userId, String reason, UUID actorUserId) {
        repository.findByTenantIdAndUserIdAndStatus(tenantId, userId, UserSigningKey.Status.active)
                .ifPresent(key -> revoke(key, reason, actorUserId));
    }

    /**
     * @throws com.oak.dms.common.exception.ResourceNotFoundException no active key for this user
     */
    @Transactional
    public UserSigningKey revoke(UUID tenantId, UUID userId, String reason, UUID actorUserId) {
        UserSigningKey key = repository.findByTenantIdAndUserIdAndStatus(tenantId, userId, UserSigningKey.Status.active)
                .orElseThrow(() -> new com.oak.dms.common.exception.ResourceNotFoundException("UserSigningKey", userId));
        return revoke(key, reason, actorUserId);
    }

    private UserSigningKey revoke(UserSigningKey key, String reason, UUID actorUserId) {
        key.setStatus(UserSigningKey.Status.revoked);
        key.setRevokedAt(OffsetDateTime.now());
        key.setRevokedBy(actorUserId);
        key.setRevokeReason(reason);
        UserSigningKey saved = repository.save(key);

        auditService.event(AuditActionCatalog.SIGNING_KEY_REVOKED)
                .objectType(AuditObjectTypes.SIGNING_KEY)
                .objectId(saved.getKeyId())
                .resourceName(saved.getKeyName())
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .newValue(Map.of("userId", saved.getUserId(), "generation", saved.getGeneration()))
                .log();
        return saved;
    }

    static SigningBackend backendOf(UserSigningKey key) {
        return SigningBackend.fromWireValue(key.getBackend());
    }
}
