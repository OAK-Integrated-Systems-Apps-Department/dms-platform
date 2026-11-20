package com.oak.dms.reporting;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report audit action the platform declares is raised somewhere in the reporting module, and nothing in the module writes an
 * audit payload from a report's definition or its data. A declared action nobody emits is a gap in the trail an auditor will find first.
 */
class ReportAuditCompletenessTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "oak", "dms", "reporting");

    private static String allSource() throws Exception {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).map(p -> {
                try {
                    return Files.readString(p);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.joining("\n"));
        }
    }

    @Test
    void everyDeclaredReportActionIsEmittedByTheModule() throws Exception {
        String source = allSource();
        List<String> declared = new ArrayList<>();
        for (Field f : AuditActionCatalog.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("REPORT_") && f.getType() == String.class) {
                declared.add(f.getName());
            }
        }
        assertThat(declared).as("the report actions in the catalogue").hasSizeGreaterThanOrEqualTo(18);
        List<String> silent = declared.stream().filter(name -> !source.contains("AuditActionCatalog." + name)).toList();
        assertThat(silent).as("declared but never emitted").isEmpty();
    }

    @Test
    void everyEmissionStatesItsOutcomeAndObject() throws Exception {
        String source = allSource();
        int from = 0;
        int events = 0;
        while ((from = source.indexOf("audit.event(", from)) >= 0) {
            int end = source.indexOf(".log()", from);
            assertThat(end).as("an audit.event(...) without log()").isGreaterThan(from);
            String call = source.substring(from, end);
            assertThat(call).as("object type in: " + call).contains("objectType(");
            assertThat(call).as("outcome in: " + call).contains("outcome(");
            from = end;
            events++;
        }
        assertThat(events).isGreaterThan(10);
    }

    @Test
    void noAuditPayloadCarriesAReportsDefinitionFiltersOrRecipientAddresses() throws Exception {
        String source = allSource();
        int from = 0;
        while ((from = source.indexOf("audit.event(", from)) >= 0) {
            int end = source.indexOf(".log()", from);
            String call = source.substring(from, end).toLowerCase();
            for (String forbidden : List.of("definition", "filters", "recipientref", "address", "email")) {
                assertThat(call).as("'" + forbidden + "' in an audit call: " + call).doesNotContain(forbidden);
            }
            from = end;
        }
    }
}
