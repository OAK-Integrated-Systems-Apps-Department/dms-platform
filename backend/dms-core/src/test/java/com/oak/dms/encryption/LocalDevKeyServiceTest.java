package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.encryption.KeyEncryptionService.DataKey;
import com.oak.dms.encryption.KeyEncryptionService.KeyServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.oak.dms.encryption.KeyEncryptionService.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The development key file: per-tenant keys, rotation, the backup copy, and the startup check. */
class LocalDevKeyServiceTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String tenantA = UUID.randomUUID().toString();
    private final String tenantB = UUID.randomUUID().toString();

    @Test
    void wrapsAndUnwraps_andRotationKeepsOldKeysReadable_untilRewrapped() {
        LocalDevKeyService keys = new LocalDevKeyService(dir.resolve("keys.json"), mapper, true);
        DataKey first = keys.generateDataKey(tenantA);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.wrapped()).startsWith("dmsdev:v1:");

        assertThat(keys.rotate(tenantA).latestVersion()).isEqualTo(2);
        assertThat(keys.unwrap(tenantA, first.wrapped())).isEqualTo(first.plaintext()); // still readable
        assertThat(keys.generateDataKey(tenantA).version()).isEqualTo(2);

        String rewrapped = keys.rewrap(tenantA, first.wrapped());
        assertThat(KeyEncryptionService.versionOf(rewrapped)).isEqualTo(2);
        assertThat(keys.unwrap(tenantA, rewrapped)).isEqualTo(first.plaintext());

        // Another tenant's key is untouched by tenant A's rotation.
        assertThat(keys.info(tenantB).latestVersion()).isEqualTo(1);

        // A restart reads the same keys back.
        LocalDevKeyService reloaded = new LocalDevKeyService(dir.resolve("keys.json"), mapper, false);
        assertThat(reloaded.unwrap(tenantA, first.wrapped())).isEqualTo(first.plaintext());
        assertThat(reloaded.info(tenantA).latestVersion()).isEqualTo(2);
        assertThat(reloaded.info(tenantA).managed()).isFalse();
    }

    @Test
    void oneTenantsWrappedKey_neverUnwrapsAsAnotherTenant() {
        LocalDevKeyService keys = new LocalDevKeyService(dir.resolve("keys.json"), mapper, true);
        DataKey forA = keys.generateDataKey(tenantA);
        keys.generateDataKey(tenantB); // B has its own key too

        assertThatThrownBy(() -> keys.unwrap(tenantB, forA.wrapped())).isInstanceOf(KeyServiceException.class);
        assertThatThrownBy(() -> keys.unwrap(PLATFORM, forA.wrapped())).isInstanceOf(KeyServiceException.class);

        // Moving a key to another scope is an explicit wrap, as the re-wrap job does.
        String moved = keys.wrap(tenantB, forA.plaintext());
        assertThat(keys.unwrap(tenantB, moved)).isEqualTo(forA.plaintext());
    }

    @Test
    void aLostKeyFile_isRestoredFromItsBackup_andNeverReplacedWithoutOne() throws Exception {
        Path keyFile = dir.resolve("volume/keys.json");
        Path backup = dir.resolve("backup/keys.json");
        LocalDevKeyService keys = new LocalDevKeyService(keyFile, backup, mapper, true);
        DataKey dataKey = keys.generateDataKey(tenantA);
        assertThat(Files.exists(backup)).isTrue();

        Files.delete(keyFile); // the Docker volume is gone
        LocalDevKeyService restored = new LocalDevKeyService(keyFile, backup, mapper, false);
        assertThat(restored.unwrap(tenantA, dataKey.wrapped())).isEqualTo(dataKey.plaintext());
        assertThat(Files.exists(keyFile)).isTrue();

        Files.delete(keyFile);
        Files.delete(backup);
        assertThatThrownBy(() -> new LocalDevKeyService(keyFile, backup, mapper, false))
                .isInstanceOf(KeyServiceException.class).hasMessageContaining("restore the file");
    }

    @Test
    void destroyingATenantsKey_removesItFromTheFileAndItsBackup_forGood() throws Exception {
        Path keyFile = dir.resolve("keys.json");
        Path backup = dir.resolve("backup/keys.json");
        LocalDevKeyService keys = new LocalDevKeyService(keyFile, backup, mapper, true);
        var forA = keys.generateDataKey("tenant-a");
        var forB = keys.generateDataKey("tenant-b");

        keys.destroy("tenant-a");
        keys.destroy("tenant-a"); // already gone: fine, so a failed shred can be retried

        assertThatThrownBy(() -> keys.unwrap("tenant-a", forA.wrapped()))
                .isInstanceOf(KeyEncryptionService.KeyServiceException.class);
        assertThat(keys.unwrap("tenant-b", forB.wrapped())).isEqualTo(forB.plaintext());
        // Neither copy can bring it back.
        assertThat(Files.readString(keyFile)).doesNotContain("tenant-a");
        assertThat(Files.readString(backup)).doesNotContain("tenant-a");
        assertThat(new LocalDevKeyService(keyFile, backup, mapper, false).hasScope("tenant-a")).isFalse();
        assertThatThrownBy(() -> keys.destroy(KeyEncryptionService.PLATFORM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOriginalSingleKeyFile_isStillReadAsThePlatformKey() throws Exception {
        Path keyFile = dir.resolve("old.json");
        LocalDevKeyService original = new LocalDevKeyService(keyFile, mapper, true);
        DataKey platformKey = original.generateDataKey(PLATFORM);
        String scopes = Files.readString(keyFile);
        // The V55-era layout: {"keys": [...]} - same key material, older shape.
        String legacy = scopes.replaceFirst("\\{\"scopes\":\\{\"platform\":(\\[.*?])}}", "{\"keys\":$1}");
        Files.writeString(keyFile, legacy);

        LocalDevKeyService reread = new LocalDevKeyService(keyFile, mapper, false);
        assertThat(reread.unwrap(PLATFORM, platformKey.wrapped())).isEqualTo(platformKey.plaintext());
    }

    // ------------------------------------------------------------------ several instances on one file

    @Test
    void aStaleInstanceSavingItsOwnNewScope_doesNotDropTheScopeAnotherInstanceCreatedMeanwhile() {
        Path file = dir.resolve("shared.json");
        LocalDevKeyService early = new LocalDevKeyService(file, mapper, true);   // reads the file now
        LocalDevKeyService other = new LocalDevKeyService(file, mapper, false);
        DataKey madeByOther = other.generateDataKey(tenantA);                    // tenant A's scope is added after

        early.generateDataKey(tenantB);                                          // the stale one saves its own

        LocalDevKeyService reloaded = new LocalDevKeyService(file, mapper, false);
        assertThat(reloaded.hasScope(tenantA)).as("tenant A's scope survived").isTrue();
        assertThat(reloaded.hasScope(tenantB)).isTrue();
        assertThat(reloaded.unwrap(tenantA, madeByOther.wrapped())).isEqualTo(madeByOther.plaintext());
    }

    @Test
    void anInstanceThatDoesntKnowAScopeYet_findsItInTheFile_beforeGivingUp() {
        Path file = dir.resolve("shared.json");
        LocalDevKeyService early = new LocalDevKeyService(file, mapper, true);
        LocalDevKeyService other = new LocalDevKeyService(file, mapper, false);
        DataKey madeByOther = other.generateDataKey(tenantA);

        // Never saw tenant A at all, yet can open what the other wrapped - and doesn't invent a second key for it.
        assertThat(early.unwrap(tenantA, madeByOther.wrapped())).isEqualTo(madeByOther.plaintext());
        assertThat(early.generateDataKey(tenantA).version()).isEqualTo(1);
        assertThat(early.unwrap(tenantA, madeByOther.wrapped())).isEqualTo(madeByOther.plaintext());
    }

    @Test
    void aRotationDoneByAnotherInstance_isTakenIn_notOverwrittenWithTheOlderKeys() {
        Path file = dir.resolve("shared.json");
        LocalDevKeyService early = new LocalDevKeyService(file, mapper, true);
        DataKey v1 = early.generateDataKey(tenantA);
        LocalDevKeyService other = new LocalDevKeyService(file, mapper, false);
        other.rotate(tenantA);                                                    // now version 2 in the file

        early.generateDataKey(tenantB);                                           // a save from the stale instance

        LocalDevKeyService reloaded = new LocalDevKeyService(file, mapper, false);
        assertThat(reloaded.info(tenantA).latestVersion()).isEqualTo(2);
        assertThat(reloaded.unwrap(tenantA, v1.wrapped())).isEqualTo(v1.plaintext());
    }

    @Test
    void aScopeDestroyedHere_isNotBroughtBackByTheFile_andStaysGoneForOthers() {
        Path file = dir.resolve("shared.json");
        LocalDevKeyService first = new LocalDevKeyService(file, mapper, true);
        first.generateDataKey(tenantA);
        first.generateDataKey(tenantB);

        first.destroy(tenantA);

        assertThat(first.hasScope(tenantA)).isFalse();
        LocalDevKeyService reloaded = new LocalDevKeyService(file, mapper, false);
        assertThat(reloaded.hasScope(tenantA)).isFalse();
        assertThat(reloaded.hasScope(tenantB)).isTrue();
    }

    @Test
    void aKeyFileThatDoesntMatchTheStoredKeys_isRefusedAtStart() {
        LocalDevKeyService real = new LocalDevKeyService(dir.resolve("real.json"), mapper, true);
        String wrapped = real.generateDataKey(tenantA).wrapped();
        StorageObjectKeyRepository repository = mock(StorageObjectKeyRepository.class);
        when(repository.sampleWrappedKeys(LocalDevKeyService.PROVIDER))
                .thenReturn(List.<Object[]>of(new Object[]{tenantA, wrapped}));

        EncryptionConfig.verifyAgainstStoredKeys(real, repository); // the right file: fine

        LocalDevKeyService other = new LocalDevKeyService(dir.resolve("other.json"), mapper, true);
        assertThatThrownBy(() -> EncryptionConfig.verifyAgainstStoredKeys(other, repository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("doesn't match the encrypted objects");
    }
}
