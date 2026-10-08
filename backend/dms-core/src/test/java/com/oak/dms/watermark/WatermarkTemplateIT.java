package com.oak.dms.watermark;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.dto.WatermarkTemplateRequest;
import com.oak.dms.watermark.dto.WatermarkTemplateResponse;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import com.oak.dms.watermark.service.WatermarkTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Watermark templates end to end against the real schema (V68): the two
 * rules the feature leans on - a tenant sees only its own templates plus the
 * system ones, and nobody edits a system one - plus the label uniqueness the
 * partial unique index has to get right for a NULL tenant.
 * <p>
 * A fresh tenant and user per test, like {@code DocumentUploadIT} and
 * {@code AccessDeniedAuditIT}, so nothing here can be affected by (or leak
 * into) another test sharing this suite's Postgres.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkTemplateIT extends WatermarkFixtures {

    /** Seeded by V68 with a fixed id so it is the same row in every environment. */
    private static final UUID SYSTEM_CONFIDENTIAL = UUID.fromString("d0cc0000-0000-4000-8000-000000000001");

    @Autowired
    private WatermarkTemplateService service;
    @Autowired
    private WatermarkTemplateRepository repository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theFourSystemTemplatesAreSeededReadOnlyAndVisibleToEveryTenant() {
        TenantFixture first = tenant();
        TenantFixture second = tenant();

        asUser(first);
        List<WatermarkTemplateResponse> forFirst = service.list(false);
        asUser(second);
        List<WatermarkTemplateResponse> forSecond = service.list(false);

        for (List<WatermarkTemplateResponse> seen : List.of(forFirst, forSecond)) {
            assertThat(seen).extracting(WatermarkTemplateResponse::label)
                    .contains("CONFIDENTIAL", "DRAFT", "INTERNAL USE ONLY", "DO NOT COPY");
            assertThat(seen).filteredOn(WatermarkTemplateResponse::systemTemplate)
                    .allSatisfy(t -> {
                        assertThat(t.editable()).isFalse();
                        assertThat(t.active()).isTrue();
                    });
        }
        // System templates sort first, so the familiar four head the picker.
        assertThat(forFirst.get(0).systemTemplate()).isTrue();
    }

    @Test
    void aTenantNeverSeesAnotherTenantsTemplate() {
        TenantFixture owner = tenant();
        asUser(owner);
        WatermarkTemplateResponse mine = service.create(request("Project Falcon"));

        TenantFixture stranger = tenant();
        asUser(stranger);
        assertThat(service.list(true)).extracting(WatermarkTemplateResponse::templateId)
                .doesNotContain(mine.templateId());
        // Not merely hidden from the list - unreachable by id, as if it never existed.
        assertThatThrownBy(() -> service.get(mine.templateId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requireUsable(mine.templateId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aTenantCannotEditOrDeleteAnotherTenantsTemplate() {
        TenantFixture owner = tenant();
        asUser(owner);
        WatermarkTemplateResponse mine = service.create(request("Owner only"));

        TenantFixture stranger = tenant();
        asUser(stranger);
        assertThatThrownBy(() -> service.update(mine.templateId(), request("Hijacked")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(mine.templateId()))
                .isInstanceOf(ResourceNotFoundException.class);

        asUser(owner);
        assertThat(service.get(mine.templateId()).label()).isEqualTo("Owner only");
    }

    @Test
    void aSystemTemplateIsReadOnlyEvenToTheTenantsOwnAdmin() {
        asUser(tenant());

        // A 403 that says why, not a 404: the caller can see this id in
        // their own list, so pretending it is missing would only confuse.
        assertThatThrownBy(() -> service.update(SYSTEM_CONFIDENTIAL, request("Mine now")))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("built-in template")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> service.delete(SYSTEM_CONFIDENTIAL))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        WatermarkTemplate untouched = repository.findById(SYSTEM_CONFIDENTIAL).orElseThrow();
        assertThat(untouched.getLabel()).isEqualTo("CONFIDENTIAL");
        assertThat(untouched.isDeleted()).isFalse();
        assertThat(untouched.getTenantId()).isNull();
    }

    @Test
    void aTenantMayReuseASystemTemplatesLabelForItsOwn() {
        // The unique index scopes system templates to their own namespace, so
        // "CONFIDENTIAL" being taken platform-wide must not stop a tenant
        // having its own differently-worded CONFIDENTIAL.
        asUser(tenant());
        WatermarkTemplateResponse own = service.create(request("CONFIDENTIAL"));
        assertThat(own.systemTemplate()).isFalse();
        assertThat(own.editable()).isTrue();
        assertThat(service.list(false)).filteredOn(t -> t.label().equals("CONFIDENTIAL")).hasSize(2);
    }

    @Test
    void aLabelIsUniquePerTenantIgnoringCase_butTheSameLabelInAnotherTenantIsFine() {
        TenantFixture first = tenant();
        asUser(first);
        service.create(request("Quarterly Report"));

        assertThatThrownBy(() -> service.create(request("quarterly report")))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("already exists")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        asUser(tenant());
        assertThat(service.create(request("Quarterly Report")).label()).isEqualTo("Quarterly Report");
    }

    @Test
    void aDeletedLabelBecomesFreeAgain() {
        asUser(tenant());
        WatermarkTemplateResponse first = service.create(request("Reusable"));
        service.delete(first.templateId());

        WatermarkTemplateResponse second = service.create(request("Reusable"));
        assertThat(second.templateId()).isNotEqualTo(first.templateId());
    }

    @Test
    void createStoresOnlyTheStyleFieldsSuppliedAndLeavesTheRestNullForTheSystemDefault() {
        asUser(tenant());
        WatermarkTemplateResponse created = service.create(new WatermarkTemplateRequest(
                "Big and red", "SEE ME", 120, null, null, "#FF0000", "centered", null, null));

        assertThat(created.fontSize()).isEqualTo(120);
        assertThat(created.color()).isEqualTo("#ff0000");
        assertThat(created.placement()).isEqualTo("centered");
        // Null means "the system default", which is not the same as a value.
        assertThat(created.opacity()).isNull();
        assertThat(created.rotation()).isNull();
        assertThat(created.pageScope()).isNull();
        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
    }

    @Test
    void updateIsAWholeBodyPutThatClearsOmittedStyleFieldsAndBumpsTheVersion() {
        asUser(tenant());
        WatermarkTemplateResponse created = service.create(new WatermarkTemplateRequest(
                "Styled", "DRAFT", 120, 50, 30, "#112233", "corner", "first-page-only", null));

        WatermarkTemplateResponse updated = service.update(created.templateId(), new WatermarkTemplateRequest(
                "Styled", "FINAL", null, null, null, null, null, null, null));

        assertThat(updated.defaultText()).isEqualTo("FINAL");
        assertThat(updated.fontSize()).isNull();
        assertThat(updated.opacity()).isNull();
        assertThat(updated.rotation()).isNull();
        assertThat(updated.color()).isNull();
        assertThat(updated.placement()).isNull();
        assertThat(updated.pageScope()).isNull();
        // The version is what a job records, so it has to move on every edit.
        assertThat(updated.version()).isEqualTo(created.version() + 1);
    }

    @Test
    void retiringATemplateKeepsItButStopsItBeingChosen() {
        asUser(tenant());
        WatermarkTemplateResponse created = service.create(request("Seasonal"));
        assertThat(service.requireUsable(created.templateId()).getLabel()).isEqualTo("Seasonal");

        service.update(created.templateId(), new WatermarkTemplateRequest(
                "Seasonal", "SEASONAL", null, null, null, null, null, null, false));

        assertThat(service.list(false)).extracting(WatermarkTemplateResponse::templateId)
                .doesNotContain(created.templateId());
        assertThat(service.list(true)).extracting(WatermarkTemplateResponse::templateId)
                .contains(created.templateId());
        assertThatThrownBy(() -> service.requireUsable(created.templateId()))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void deleteIsSoftSoAJobAlreadyMadeFromTheTemplateKeepsItsProvenance() {
        asUser(tenant());
        WatermarkTemplateResponse created = service.create(request("Temporary"));
        service.delete(created.templateId());

        assertThatThrownBy(() -> service.get(created.templateId())).isInstanceOf(ResourceNotFoundException.class);
        // The row survives, so watermark_jobs.template_id still resolves.
        WatermarkTemplate row = repository.findById(created.templateId()).orElseThrow();
        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getDeletedAt()).isNotNull();
    }

    @Test
    void createUpdateAndDeleteAreAuditedUnderModuleDwWithBeforeAndAfterValues() {
        asUser(tenant());
        WatermarkTemplateResponse created = service.create(request("Audited"));
        service.update(created.templateId(), new WatermarkTemplateRequest(
                "Audited", "CHANGED", null, null, null, null, null, null, null));
        service.delete(created.templateId());

        assertThat(auditCount("WATERMARK_TEMPLATE_CREATED", created.templateId())).isEqualTo(1);
        assertThat(auditCount("WATERMARK_TEMPLATE_UPDATED", created.templateId())).isEqualTo(1);
        assertThat(auditCount("WATERMARK_TEMPLATE_DELETED", created.templateId())).isEqualTo(1);
        // The module code the audit log filters on - V7's 'DW'.
        assertThat(jdbc.queryForObject("select module from audit_events where action = ? and object_id = ?",
                String.class, "WATERMARK_TEMPLATE_CREATED", created.templateId())).isEqualTo("DW");
        assertThat(jdbc.queryForObject("select previous_value is not null from audit_events "
                        + "where action = ? and object_id = ?",
                Boolean.class, "WATERMARK_TEMPLATE_UPDATED", created.templateId())).isTrue();
    }

    @Test
    void outOfRangeStyleValuesAreRefusedBeforeAnythingIsWritten() {
        asUser(tenant());
        assertThatThrownBy(() -> service.create(new WatermarkTemplateRequest(
                "Too big", "X", 5000, null, null, null, null, null, null)))
                .isInstanceOf(WatermarkException.class);
        assertThatThrownBy(() -> service.create(new WatermarkTemplateRequest(
                "Invisible", "X", null, 0, null, null, null, null, null)))
                .isInstanceOf(WatermarkException.class);

        assertThat(service.list(true)).extracting(WatermarkTemplateResponse::label)
                .doesNotContain("Too big", "Invisible");
    }

    // ----------------------------------------------------------- fixtures

    private static WatermarkTemplateRequest request(String label) {
        return new WatermarkTemplateRequest(label, label.toUpperCase(java.util.Locale.ROOT),
                null, null, null, null, null, null, null);
    }

    private void asUser(TenantFixture fixture) {
        TenantContext.clear();
        TenantContext.set(fixture.tenantId(), fixture.userId());
    }

    private TenantFixture tenant() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Watermark template test tenant " + UUID.randomUUID())
                .domain("watermark-template-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        trackTenant(tenant.getTenantId());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@watermark-template-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());

        return new TenantFixture(tenant.getTenantId(), user.getUserId());
    }

    private record TenantFixture(UUID tenantId, UUID userId) {
    }
}
