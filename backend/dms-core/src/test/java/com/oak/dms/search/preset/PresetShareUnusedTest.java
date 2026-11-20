package com.oak.dms.search.preset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Presets are private: the share permission is declared but nothing may check, grant or use it. */
class PresetShareUnusedTest {

    @Test
    void noMainSourceReferencesTheSharePermissionExceptItsDeclaration() throws IOException {
        List<String> users;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            users = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    String s = Files.readString(f);
                    return s.contains("DSR_MANAGE_PRESETS_SHARE") || s.contains("manage-saved-search-presets.share");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).map(f -> f.getFileName().toString()).collect(Collectors.toList());
        }
        assertThat(users).containsExactly("PermissionCodes.java");
    }

    @Test
    void noMigrationAfterTheSeedGrantsIt() throws IOException {
        // V141 grants create, update and delete only; the share code appears in no later migration
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/db/migration"))) {
            List<String> grantsShare = files.filter(f -> f.getFileName().toString().matches("V1[3-9][0-9]__.*\\.sql"))
                    .filter(f -> {
                        try {
                            return Files.readString(f).contains("manage-saved-search-presets.share");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).map(f -> f.getFileName().toString()).toList();
            assertThat(grantsShare).isEmpty();
        }
    }
}
