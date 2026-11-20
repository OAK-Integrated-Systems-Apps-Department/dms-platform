package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBX-04, the defect itself: nothing ever deletes an outbox row, so published rows (and failed ones) accumulate for ever.
 * Written against the ORIGINAL source first (it compiles against both: it uses only reflection and a scan of the main sources).
 * On the original both tests fail - there is no cleanup component and no DELETE against {@code outbox_events} anywhere.
 */
class OutboxCleanupObx04DefectTest {

    @Test
    void aScheduledCleanupOfPublishedOutboxRowsMustExist() throws Exception {
        Class<?> cleanup = Class.forName("com.oak.dms.outbox.OutboxCleanup");

        Method[] methods = cleanup.getDeclaredMethods();
        boolean scheduled = false;
        for (Method method : methods) {
            scheduled |= method.isAnnotationPresent(Scheduled.class);
        }
        assertThat(scheduled).as("the cleanup runs on a schedule").isTrue();
    }

    @Test
    void somethingMustDeletePublishedRowsFromTheOutboxTable() throws Exception {
        Path main = Path.of("src", "main", "java");
        boolean deletes;
        try (Stream<Path> files = Files.walk(main)) {
            deletes = files.filter(p -> p.toString().endsWith(".java")).anyMatch(p -> {
                try {
                    String text = Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
                    return text.contains("delete from outbox_events");
                } catch (Exception e) {
                    return false;
                }
            });
        }

        assertThat(deletes).as("no code deletes from outbox_events: the table only ever grows").isTrue();
    }
}
