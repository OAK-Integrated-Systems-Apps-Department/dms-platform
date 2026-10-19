package com.oak.dms.signature.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.signature.enums.SigningBackend;
import com.oak.dms.signature.helper.PemKeys;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Development-only signer: one ECDSA P-256 key pair per key name, in a JSON
 * file on its own volume - never in the database or git. Mirrors
 * {@code LocalDevKeyService}'s own file-handling conventions (atomic
 * temp-file-then-move write, POSIX 600 where supported). NOT a key
 * management service: {@code SignatureConfig} refuses to start with this
 * signer when {@code dms.encryption.require-managed-keys} is set, or when
 * {@code dms.signature.allow-local-dev} is false.
 */
@Slf4j
public class LocalDevDocumentSigner implements DocumentSigner {

    private final Path keyFile;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> keys = new TreeMap<>();

    /** One key pair: the private key (PKCS8, base64) and its public key PEM, kept together for atomic saves. */
    private record Entry(String privateKeyPkcs8Base64, String publicKeyPem) {
    }

    public LocalDevDocumentSigner(Path keyFile, ObjectMapper mapper) {
        this.keyFile = keyFile;
        this.mapper = mapper;
        if (Files.exists(keyFile)) {
            load();
        }
    }

    @Override
    public SigningBackend backend() {
        return SigningBackend.local_dev;
    }

    @Override
    public String ensureKey(String keyName) {
        lock.lock();
        try {
            Entry existing = keys.get(keyName);
            if (existing != null) {
                return existing.publicKeyPem();
            }
            KeyPairGenerator generator;
            try {
                generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("The JVM has no EC/secp256r1 key pair generator", e);
            }
            KeyPair pair = generator.generateKeyPair();
            String publicKeyPem = PemKeys.encodePublicKey(pair.getPublic());
            keys.put(keyName, new Entry(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), publicKeyPem));
            save();
            log.warn("Created a development signing key {} at {} - for local development only; "
                    + "production must use OpenBao (dms.signature.provider=openbao)", keyName, keyFile);
            return publicKeyPem;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] sign(String keyName, byte[] sha256Digest) {
        Entry entry = keys.get(keyName);
        if (entry == null) {
            throw new IllegalStateException("No development signing key " + keyName + " - call ensureKey first");
        }
        try {
            PrivateKey privateKey = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(entry.privateKeyPkcs8Base64())));
            // NONEwithECDSA signs a digest the caller already hashed, exactly as
            // OpenBao Transit's prehashed=true does - the same wire shape either way.
            Signature signature = Signature.getInstance("NONEwithECDSA");
            signature.initSign(privateKey);
            signature.update(sha256Digest);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign with the development signing key " + keyName, e);
        }
    }

    private void load() {
        try {
            Map<String, Entry> loaded = mapper.readValue(keyFile.toFile(), mapper.getTypeFactory()
                    .constructMapType(TreeMap.class, String.class, Entry.class));
            keys.putAll(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the development signing key file " + keyFile, e);
        }
    }

    /** Written to a temp file and moved into place, so a crash never leaves half a key file. */
    private void save() {
        try {
            Path dir = keyFile.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "dms-signature-keys", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem (Windows dev machine) - nothing more to tighten.
            }
            mapper.writeValue(temp.toFile(), keys);
            Files.move(temp, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the development signing key file " + keyFile, e);
        }
    }
}
