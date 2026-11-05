package com.oak.dms.encryption;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Development-only key service: versioned AES-256 master keys, one set per
 * scope (tenant), in a JSON file on its own volume - never in the database or
 * git. It wraps data keys with AES-256-GCM the way OpenBao Transit does, so
 * the rest of the system can't tell the difference - but it is NOT a key
 * management service: production sets
 * {@code dms.encryption.require-managed-keys=true}, which refuses to start
 * with it. Wrapped keys look like {@code dmsdev:v1:<base64>}.
 * <p>
 * Every change is written to the key file and, when configured, to a backup
 * copy outside Docker (locally: a gitignored folder of the repository). If the
 * key file's volume is lost, the backup is restored on start.
 * <p>
 * More than one instance can share one file - the integration tests keep several Spring contexts alive at once, each
 * with its own instance. So an instance never writes only what it holds: it first takes in what the others have
 * added since it last read the file, and looks in the file again before deciding that a scope is unknown. Without
 * that, an instance that read the file early would, on its next save, drop every scope another one created after -
 * and the objects encrypted under them could never be opened again.
 */
@Slf4j
public class LocalDevKeyService implements KeyEncryptionService {

    public static final String PROVIDER = "local-dev";
    private static final String PREFIX = "dmsdev";
    private static final int IV_BYTES = 12;

    private final Path keyFile;
    private final Path backupFile;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<MasterKey>> scopes = new TreeMap<>();
    /** Scopes destroyed through this instance - never brought back by taking in what the file still says. */
    private final Set<String> destroyed = new HashSet<>();
    /** One lock for every instance in the process: reading the file, merging and writing it happen as one step. */
    private static final Object FILE_LOCK = new Object();

    /** One master-key version. */
    public record MasterKey(int version, String key) {
    }

    /**
     * The key file. {@code keys} is the original single-key format (read as the platform scope);
     * {@code scopes} is written from V57 on.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeyFile(List<MasterKey> keys, Map<String, List<MasterKey>> scopes) {
    }

    /**
     * @param backupFile a second copy kept in step with the key file; null = none
     * @param mayCreate  start a new key file when neither file exists - false once objects depend on it
     */
    public LocalDevKeyService(Path keyFile, Path backupFile, ObjectMapper mapper, boolean mayCreate) {
        this.keyFile = keyFile;
        this.backupFile = backupFile;
        this.mapper = mapper;
        Path source = Files.exists(keyFile) ? keyFile
                : backupFile != null && Files.exists(backupFile) ? backupFile : null;
        if (source != null) {
            load(source);
            if (source == backupFile) {
                log.warn("The development key file {} was missing - restored it from the backup {}", keyFile, backupFile);
                save();
            } else if (backupFile != null && !Files.exists(backupFile)) {
                save(); // first run with a backup configured: write it
            }
        } else if (mayCreate) {
            scopes.put(PLATFORM, List.of(new MasterKey(1, newKey())));
            save();
            log.warn("Created a development encryption key file at {} - for local development only; "
                    + "production must use OpenBao (dms.encryption.provider=openbao)", keyFile);
        } else {
            throw new KeyServiceException("The development key file " + keyFile + " is missing"
                    + (backupFile != null ? " and so is its backup " + backupFile : "") + ", but objects encrypted "
                    + "with it exist - restore the file (or its volume) instead of creating a new key, or those "
                    + "objects can never be decrypted.");
        }
    }

    public LocalDevKeyService(Path keyFile, ObjectMapper mapper, boolean mayCreate) {
        this(keyFile, null, mapper, mayCreate);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public synchronized DataKey generateDataKey(String scope) {
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        MasterKey latest = latest(scope, true);
        return new DataKey(dataKey, wrap(scope, dataKey, latest), latest.version());
    }

    @Override
    public synchronized byte[] unwrap(String scope, String wrappedKey) {
        int version = KeyEncryptionService.versionOf(wrappedKey);
        MasterKey master = find(scope, version);
        if (master == null) {
            takeInFile(); // another instance may have created it since this one read the file
            master = find(scope, version);
        }
        if (master == null) {
            throw new KeyServiceException("Unknown development key version " + version + " for scope " + scope);
        }
        byte[] payload = Base64.getDecoder().decode(wrappedKey.split(":", 3)[2]);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(master), new GCMParameterSpec(128, payload, 0, IV_BYTES));
            cipher.updateAAD(aad(scope));
            return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new KeyServiceException("Could not unwrap the data key", e);
        }
    }

    @Override
    public synchronized String rewrap(String scope, String wrappedKey) {
        byte[] dataKey = unwrap(scope, wrappedKey);
        try {
            return wrap(scope, dataKey, latest(scope, true));
        } finally {
            java.util.Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public synchronized String wrap(String scope, byte[] dataKey) {
        return wrap(scope, dataKey, latest(scope, true));
    }

    @Override
    public synchronized KeyInfo info(String scope) {
        return new KeyInfo(PROVIDER, keyFile.getFileName() + "#" + scope, latest(scope, true).version(), 1, false);
    }

    @Override
    public synchronized KeyInfo rotate(String scope) {
        List<MasterKey> next = new ArrayList<>(scopes.getOrDefault(scope, List.of()));
        next.add(new MasterKey(latest(scope, true).version() + 1, newKey()));
        scopes.put(scope, List.copyOf(next));
        save();
        return info(scope);
    }

    @Override
    public synchronized void destroy(String scope) {
        KeyEncryptionService.requireTenantScope(scope);
        destroyed.add(scope);
        if (scopes.remove(scope) != null) {
            save(); // the backup copy too - otherwise a restore would bring the key back
        }
    }

    /** Whether the file has keys for a scope - for the startup check against stored objects. */
    synchronized boolean hasScope(String scope) {
        if (!scopes.containsKey(scope)) {
            takeInFile();
        }
        return scopes.containsKey(scope);
    }

    private MasterKey find(String scope, int version) {
        return scopes.getOrDefault(scope, List.of()).stream().filter(k -> k.version() == version).findFirst().orElse(null);
    }

    private String wrap(String scope, byte[] dataKey, MasterKey master) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret(master), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(scope));
            byte[] sealed = cipher.doFinal(dataKey);
            byte[] payload = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, payload, 0, IV_BYTES);
            System.arraycopy(sealed, 0, payload, IV_BYTES, sealed.length);
            return PREFIX + ":v" + master.version() + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new KeyServiceException("Could not wrap the data key", e);
        }
    }

    /** A key wrapped for one scope can't be unwrapped as another. (Platform keeps its original value.) */
    private static byte[] aad(String scope) {
        String aad = PLATFORM.equals(scope) ? "dms-local-dev-wrap" : "dms-local-dev-wrap:" + scope;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    /** The scope's newest key; a scope that has none yet gets its first key when {@code create}. */
    private MasterKey latest(String scope, boolean create) {
        List<MasterKey> keys = scopes.get(scope);
        if (keys == null || keys.isEmpty()) {
            takeInFile(); // it may exist already, made by another instance on this file
            keys = scopes.get(scope);
        }
        if (keys == null || keys.isEmpty()) {
            if (!create) {
                throw new KeyServiceException("No development key for scope " + scope);
            }
            keys = List.of(new MasterKey(1, newKey()));
            scopes.put(scope, keys);
            destroyed.remove(scope);
            save();
        }
        return keys.stream().reduce((a, b) -> a.version() >= b.version() ? a : b).orElseThrow();
    }

    private static SecretKeySpec secret(MasterKey master) {
        return new SecretKeySpec(Base64.getDecoder().decode(master.key()), "AES");
    }

    private String newKey() {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private void load(Path file) {
        try {
            KeyFile content = mapper.readValue(file.toFile(), KeyFile.class);
            if (content.scopes() != null) {
                scopes.putAll(content.scopes());
            }
            if (content.keys() != null && !content.keys().isEmpty()) {
                scopes.putIfAbsent(PLATFORM, content.keys()); // the original single-key format
            }
        } catch (IOException e) {
            throw new KeyServiceException("Could not read the development key file " + file, e);
        }
        if (scopes.isEmpty()) {
            throw new KeyServiceException("The development key file " + file + " has no keys");
        }
    }

    private void save() {
        synchronized (FILE_LOCK) {
            mergeFile();
            KeyFile content = new KeyFile(null, Map.copyOf(scopes));
            write(keyFile, content);
            if (backupFile != null) {
                try {
                    write(backupFile, content);
                } catch (UncheckedIOException e) {
                    log.warn("Could not update the development key backup {}: {}", backupFile, e.getMessage());
                }
            }
        }
    }

    /** Takes in what other instances have written to the file since this one read it. */
    private void takeInFile() {
        synchronized (FILE_LOCK) {
            mergeFile();
        }
    }

    /**
     * Adds to {@link #scopes} every scope the file has that this instance lacks, and any scope whose file copy has a
     * newer key (it was rotated elsewhere). Never takes a key away from this instance and never brings back a scope
     * destroyed here. Called with {@link #FILE_LOCK} held.
     */
    private void mergeFile() {
        if (!Files.exists(keyFile)) {
            return;
        }
        try {
            KeyFile content = mapper.readValue(keyFile.toFile(), KeyFile.class);
            if (content.scopes() == null) {
                return;
            }
            content.scopes().forEach((scope, keys) -> {
                if (destroyed.contains(scope) || keys == null || keys.isEmpty()) {
                    return;
                }
                List<MasterKey> mine = scopes.get(scope);
                if (mine == null || mine.isEmpty() || newest(keys) > newest(mine)) {
                    scopes.put(scope, keys);
                }
            });
        } catch (IOException e) {
            // An unreadable file is not worth losing this instance's keys over: it is written again from memory.
            log.warn("Could not read the development key file {} to take in other instances' keys: {}", keyFile, e.getMessage());
        }
    }

    private static int newest(List<MasterKey> keys) {
        return keys.stream().mapToInt(MasterKey::version).max().orElse(0);
    }

    /** Written to a temp file and moved into place, so a crash never leaves half a key file. */
    private void write(Path target, KeyFile content) {
        try {
            Path dir = target.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "keys", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem (Windows dev machine) - nothing more to tighten.
            }
            mapper.writeValue(temp.toFile(), content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the development key file " + target, e);
        }
    }
}
