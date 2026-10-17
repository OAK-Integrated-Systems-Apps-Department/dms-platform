package com.oak.dms.monitoring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The firing shapes are tested with hand-built maps because the live stack
 * has nothing firing - and the day one does is the day this is read.
 */
class AlertsServiceTest {

    private final PrometheusClient prometheus = mock(PrometheusClient.class);
    private final AlertsService service = new AlertsService(prometheus);

    private static Map<String, Object> rule(String name, String state, String severity, String health,
                                            List<Map<String, Object>> alerts) {
        return Map.of(
                "name", name,
                "state", state,
                "health", health,
                "labels", Map.of("severity", severity, "component", "backend"),
                "annotations", Map.of("summary", "Summary of " + name,
                        "description", "{{ $value | humanize }} raw template"),
                "alerts", alerts);
    }

    private static Map<String, Object> response(List<Map<String, Object>> rules) {
        return Map.of("status", "success",
                "data", Map.of("groups", List.of(Map.of("name", "g", "rules", rules))));
    }

    private MonitoringResponse.AlertsResponse run(List<Map<String, Object>> rules) {
        when(prometheus.configured()).thenReturn(true);
        when(prometheus.alertRules()).thenReturn(response(rules));
        return service.alerts();
    }

    @Test
    void anInactiveRuleShowsItsSummaryButNotItsRawTemplate() {
        MonitoringResponse.AlertRule only = run(List.of(rule("A", "inactive", "warning", "ok", List.of())))
                .rules().get(0);
        assertThat(only.summary()).isEqualTo("Summary of A");
        // An unfired rule's description is still "{{ $value | humanize }}..."
        // and must never be shown as if it were a message.
        assertThat(only.detail()).isNull();
        assertThat(only.since()).isNull();
    }

    @Test
    void aFiringRuleCarriesTheRenderedDescriptionAndTheEarliestStart() {
        Map<String, Object> early = Map.of("activeAt", "2026-10-13T08:00:00Z",
                "annotations", Map.of("description", "7.2% are returning 5xx"));
        Map<String, Object> late = Map.of("activeAt", "2026-10-13T09:30:00Z",
                "annotations", Map.of("description", "other"));
        MonitoringResponse.AlertRule firing = run(List.of(
                rule("ApiErrorRateHigh", "firing", "critical", "ok", List.of(late, early)))).rules().get(0);

        assertThat(firing.detail()).isEqualTo("other"); // first alert's rendering
        // The earliest of the two - when the problem began, not when the
        // second instance joined it.
        assertThat(firing.since()).isEqualTo(java.time.OffsetDateTime.parse("2026-10-13T08:00:00Z").toEpochSecond());
    }

    @Test
    void mostUrgentSortsFirstAndABrokenRuleIsNotBuriedAmongTheQuietOnes() {
        List<MonitoringResponse.AlertRule> sorted = run(List.of(
                rule("Quiet", "inactive", "warning", "ok", List.of()),
                rule("PendingWarn", "pending", "warning", "ok", List.of()),
                rule("Broken", "inactive", "warning", "err", List.of()),
                rule("FiringWarn", "firing", "warning", "ok", List.of()),
                rule("FiringCrit", "firing", "critical", "ok", List.of()))).rules();

        assertThat(sorted).extracting(MonitoringResponse.AlertRule::name)
                .containsExactly("FiringCrit", "Broken", "FiringWarn", "PendingWarn", "Quiet");
        assertThat(sorted.get(1).broken()).isTrue();
    }

    @Test
    void aMissingLabelDoesNotFailTheList() {
        Map<String, Object> bare = Map.of("name", "Bare", "state", "inactive");
        MonitoringResponse.AlertRule only = run(List.of(bare)).rules().get(0);
        assertThat(only.severity()).isNull();
        assertThat(only.summary()).isNull();
        assertThat(only.broken()).isFalse();
    }

    @Test
    void anUnansweringPrometheusIsUnknownNotClear() {
        when(prometheus.configured()).thenReturn(true);
        when(prometheus.alertRules()).thenReturn(null);
        MonitoringResponse.AlertsResponse result = service.alerts();
        assertThat(result.reachable()).isFalse();
        assertThat(result.rules()).isEmpty();
        assertThat(result.error()).contains("unknown, not clear");
    }

    private static Map<String, Object> ruleNeeding(String name, String state, String requires) {
        return Map.of(
                "name", name, "state", state, "health", "ok",
                "labels", Map.of("severity", "warning"),
                "annotations", Map.of("summary", "s", "requires", requires),
                "alerts", List.of());
    }

    @Test
    void aQuietRuleWhoseInputDoesNotExistIsBlindNotFine() {
        when(prometheus.configured()).thenReturn(true);
        when(prometheus.alertRules()).thenReturn(response(List.of(
                ruleNeeding("ContainerRestarting", "inactive", "container_start_time_seconds{name!=\"\"}"),
                ruleNeeding("HostCpuHigh", "inactive", "node_cpu_seconds_total"))));
        // No series for the container selector; plenty for the host one.
        when(prometheus.vector("count(container_start_time_seconds{name!=\"\"})")).thenReturn(List.of());
        when(prometheus.vector("count(node_cpu_seconds_total)"))
                .thenReturn(List.of(new PrometheusClient.Sample(Map.of(), 20.0)));

        Map<String, Boolean> blind = new java.util.HashMap<>();
        service.alerts().rules().forEach(r -> blind.put(r.name(), r.blind()));
        assertThat(blind).containsEntry("ContainerRestarting", true).containsEntry("HostCpuHigh", false);
    }

    @Test
    void aFiringRuleIsNeverCheckedForBlindness() {
        when(prometheus.configured()).thenReturn(true);
        when(prometheus.alertRules()).thenReturn(response(List.of(
                ruleNeeding("Firing", "firing", "x"))));
        MonitoringResponse.AlertRule only = service.alerts().rules().get(0);
        // It has found data by definition; asking again would be a wasted
        // query, and a wrong answer if it ever disagreed.
        assertThat(only.blind()).isFalse();
        org.mockito.Mockito.verify(prometheus, org.mockito.Mockito.never()).vector(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ruleSharingAnInputAreOnlyAskedOnce() {
        when(prometheus.configured()).thenReturn(true);
        when(prometheus.alertRules()).thenReturn(response(List.of(
                ruleNeeding("DiskLow", "inactive", "disk"), ruleNeeding("DiskCritical", "inactive", "disk"))));
        when(prometheus.vector("count(disk)")).thenReturn(List.of(new PrometheusClient.Sample(Map.of(), 1.0)));
        service.alerts();
        org.mockito.Mockito.verify(prometheus, org.mockito.Mockito.times(1)).vector("count(disk)");
    }
}
