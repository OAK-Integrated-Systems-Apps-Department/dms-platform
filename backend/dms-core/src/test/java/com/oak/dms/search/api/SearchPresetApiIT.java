package com.oak.dms.search.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oak.dms.department.entity.Department;
import com.oak.dms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import com.oak.dms.workflow.support.WorkflowFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Saved search presets (S12): private, versioned, capped and audited; running one drops outdated filters and names them.
 * Every rule of the plan has a test here: duplicate names, overwrite, no criteria, the limit, other people's presets,
 * other organisations' presets, the permission matrix, versions, audit, and that no log line carries the keywords.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchPresetApiIT extends SearchApiSupport {

    private static final String BASE = "/api/v1/search/presets";
    private static final String CREATE = "dsr.manage-saved-search-presets.create";
    private static final String UPDATE = "dsr.manage-saved-search-presets.update";
    private static final String DELETE = "dsr.manage-saved-search-presets.delete";

    private User dave;     // may search, holds no preset-manage code
    private User erin;     // may search and only create presets

    @BeforeEach
    void presetWorld() {
        fx.role(tenantId, "Preset manager", new String[]{CREATE, UPDATE, DELETE}, alice);
        dave = fx.user(tenantId, "Dave");
        fx.role(tenantId, "Search only", new String[]{"dsr.search-documents", "docm.documents.view"}, dave);
        erin = fx.user(tenantId, "Erin");
        fx.role(tenantId, "Create only", new String[]{"dsr.search-documents", "docm.documents.view", CREATE}, erin);
        fx.role(tenantId, "Archive presets", new String[]{CREATE, UPDATE, DELETE}, archivist);
        fx.jdbc().update("delete from saved_search_settings where tenant_id = ?", tenantId);
    }

    private JsonNode create(User as, String name, Map<String, Object> criteria, int status) throws Exception {
        return call(post(BASE), fx.jwtFor(as), Map.of("name", name, "criteria", criteria), status);
    }

    private JsonNode createOk(User as, String name, String keyword) throws Exception {
        return create(as, name, Map.of("q", keyword), 201);
    }

    // ---- round trip ------------------------------------------------------------------------------------------------

    @Test
    void savesListsReadsAndRunsAPreset() throws Exception {
        doc("Zebra migration report", "zebra.txt", "zebra stripes");
        doc("Other", "other.txt", "nothing relevant");
        index();

        JsonNode saved = createOk(alice, "Zebras", "zebra");
        UUID id = UUID.fromString(saved.path("presetId").asString());
        assertThat(saved.path("name").asString()).isEqualTo("Zebras");
        assertThat(saved.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(saved.path("criteria").path("q").asString()).isEqualTo("zebra");
        assertThat(saved.path("criteria").path("schemaVersion").asInt()).isEqualTo(1);

        JsonNode list = call(get(BASE), fx.jwtFor(alice), null, 200);
        assertThat(list.path("count").asInt()).isEqualTo(1);
        assertThat(list.path("limit").asInt()).isEqualTo(50);
        assertThat(list.path("items").get(0).path("presetId").asString()).isEqualTo(id.toString());
        assertThat(list.path("items").get(0).path("runnable").asBoolean()).isTrue();
        assertThat(list.path("items").get(0).path("summary").asString()).contains("zebra");
        assertThat(call(get(BASE + "/" + id), fx.jwtFor(alice), null, 200).path("name").asString()).isEqualTo("Zebras");

        JsonNode run = call(post(BASE + "/" + id + "/run"), fx.jwtFor(alice), Map.of("size", 10), 200);
        assertThat(run.path("preset").path("name").asString()).isEqualTo("Zebras");
        assertThat(run.path("criteria").path("q").asString()).isEqualTo("zebra");
        assertThat(run.path("warnings").size()).isZero();
        assertThat(run.path("result").path("mode").asString()).isEqualTo("search");
        assertThat(titles(run.path("result"))).containsExactly("Zebra migration report");
    }

    @Test
    void aRunNeverChangesTheStoredPreset() throws Exception {
        UUID id = UUID.fromString(createOk(alice, "Stable", "anything").path("presetId").asString());
        var before = fx.jdbc().queryForMap("select criteria::text c, updated_at u from saved_searches where preset_id = ?", id);
        call(post(BASE + "/" + id + "/run"), fx.jwtFor(alice), null, 200);
        var after = fx.jdbc().queryForMap("select criteria::text c, updated_at u from saved_searches where preset_id = ?", id);
        assertThat(after).isEqualTo(before);
    }

    // ---- names -------------------------------------------------------------------------------------------------------

    @Test
    void aDuplicateNameIsAConflict_whateverTheCaseOrSpacing_andOverwriteReplacesTheCriteria() throws Exception {
        UUID id = UUID.fromString(createOk(alice, "Quarterly", "invoice").path("presetId").asString());

        JsonNode conflict = create(alice, "  QUARTERLY ", Map.of("q", "other"), 409);
        assertThat(conflict.path("code").asString()).isEqualTo("PRESET_NAME_EXISTS");
        assertThat(conflict.path("details").path("presetId").asString()).isEqualTo(id.toString());

        // another person may use the same name
        createOk(erin, "Quarterly", "x");
    }

    @Test
    void overwriteKeepsTheIdAndNeedsTheUpdatePermissionToo() throws Exception {
        UUID id = UUID.fromString(createOk(alice, "Quarterly", "invoice").path("presetId").asString());
        JsonNode replaced = call(post(BASE), fx.jwtFor(alice),
                Map.of("name", "quarterly", "criteria", Map.of("q", "receipt"), "overwrite", true), 201);
        assertThat(replaced.path("presetId").asString()).isEqualTo(id.toString());
        assertThat(replaced.path("name").asString()).isEqualTo("quarterly");
        assertThat(replaced.path("criteria").path("q").asString()).isEqualTo("receipt");
        assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isEqualTo(1);

        // erin can create but not update: creating her own works, replacing her own is refused
        createOk(erin, "Mine", "a");
        call(post(BASE), fx.jwtFor(erin), Map.of("name", "mine", "criteria", Map.of("q", "b"), "overwrite", true), 403);
    }

    @Test
    void renameToANameYouAlreadyUseIsAConflict_andNameRules() throws Exception {
        createOk(alice, "One", "a");
        UUID two = UUID.fromString(createOk(alice, "Two", "b").path("presetId").asString());
        call(put(BASE + "/" + two), fx.jwtFor(alice), Map.of("name", "ONE"), 409);
        assertThat(call(put(BASE + "/" + two), fx.jwtFor(alice), Map.of("name", "Three"), 200).path("name").asString())
                .isEqualTo("Three");
        // renaming to its own name in another case is fine
        call(put(BASE + "/" + two), fx.jwtFor(alice), Map.of("name", "THREE"), 200);
        create(alice, "   ", Map.of("q", "x"), 400);
        create(alice, "n".repeat(101), Map.of("q", "x"), 400);
        call(put(BASE + "/" + two), fx.jwtFor(alice), Map.of(), 400);
    }

    // ---- criteria ----------------------------------------------------------------------------------------------------

    @Test
    void atLeastOneCriterionIsRequired() throws Exception {
        for (Map<String, Object> none : List.of(Map.<String, Object>of(), Map.<String, Object>of("sort", "title"),
                Map.<String, Object>of("statuses", List.of("active")), Map.<String, Object>of("includeSubfolders", true),
                Map.<String, Object>of("q", "   "), Map.<String, Object>of("ascending", true))) {
            JsonNode r = create(alice, "Empty " + none.hashCode(), none, 400);
            assertThat(r.path("code").asString()).as(none.toString()).isEqualTo("PRESET_NO_CRITERIA");
        }
        assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isZero();
        // each of these alone counts
        create(alice, "Keyword", Map.of("q", "x"), 201);
        create(alice, "Dates", Map.of("createdFrom", "2026-01-01T00:00:00Z"), 201);
        create(alice, "Type", Map.of("documentTypeId", reportType.toString()), 201);
    }

    @Test
    void unknownKeysBadValuesAndInventedFiltersAreRefused() throws Exception {
        assertThat(create(alice, "A", Map.of("q", "x", "documentId", UUID.randomUUID().toString()), 400)
                .path("code").asString()).isEqualTo("PRESET_INVALID");
        create(alice, "B", Map.of("q", "x", "sort", "magic"), 400);
        create(alice, "C", Map.of("documentTypeId", "not-a-uuid"), 400);
        create(alice, "D", Map.of("createdFrom", "2026-02-01T00:00:00Z", "createdTo", "2026-01-01T00:00:00Z"), 400);
        create(alice, "E", Map.of("statuses", List.of("deleted")), 400);
        // a department that does not exist is refused at save time (it is only dropped, with a warning, when it goes stale later)
        assertThat(create(alice, "F", Map.of("departmentId", UUID.randomUUID().toString()), 400).path("code").asString())
                .isEqualTo("PRESET_INVALID");
        assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isZero();
    }

    // ---- limit --------------------------------------------------------------------------------------------------------

    @Test
    void thePerPersonLimitIsEnforced_isPerTenantConfigurable_andNeverDeletes() throws Exception {
        call(put("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), Map.of("maxPerUser", 3), 200);
        for (int i = 0; i < 3; i++) {
            createOk(alice, "P" + i, "k" + i);
        }
        JsonNode full = create(alice, "P3", Map.of("q", "k"), 409);
        assertThat(full.path("code").asString()).isEqualTo("PRESET_LIMIT_REACHED");
        assertThat(full.path("details").path("limit").asInt()).isEqualTo(3);
        // replacing one is not blocked by the limit
        call(post(BASE), fx.jwtFor(alice), Map.of("name", "p0", "criteria", Map.of("q", "new"), "overwrite", true), 201);
        // the limit is per person
        createOk(erin, "Erin's", "k");
        // deleting one frees a slot
        UUID any = UUID.fromString(call(get(BASE), fx.jwtFor(alice), null, 200).path("items").get(0).path("presetId").asString());
        call(delete(BASE + "/" + any), fx.jwtFor(alice), null, 204);
        createOk(alice, "P3", "k");
        // lowering it keeps what exists but stops additions
        call(put("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), Map.of("maxPerUser", 1), 200);
        assertThat(call(get(BASE), fx.jwtFor(alice), null, 200).path("count").asInt()).isEqualTo(3);
        create(alice, "P9", Map.of("q", "k"), 409);

        JsonNode limit = call(get("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), null, 200);
        assertThat(limit.path("maxPerUser").asInt()).isEqualTo(1);
        assertThat(limit.path("defaultMax").asInt()).isEqualTo(50);
        call(put("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), Map.of("maxPerUser", 0), 400);
        call(put("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), Map.of("maxPerUser", 501), 400);
        call(put("/api/v1/admin/search/presets/limit"), fx.jwtFor(alice), Map.of("maxPerUser", 5), 403);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'SEARCH_PRESET_LIMIT_CHANGED'", tenantId))
                .isEqualTo(2);
    }

    @Test
    void concurrentSavesNeverExceedTheLimit() throws Exception {
        call(put("/api/v1/admin/search/presets/limit"), fx.tenantAdminJwtFor(tenantAdmin), Map.of("maxPerUser", 3), 200);
        createOk(alice, "Seed", "seed");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "Race " + i;
                Callable<Integer> task = () -> mvc.perform(post(BASE).with(fx.jwtFor(alice))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("name", name, "criteria", Map.of("q", "race")))))
                        .andReturn().getResponse().getStatus();
                results.add(pool.submit(task));
            }
            int created = 0;
            int refused = 0;
            for (Future<Integer> f : results) {
                int status = f.get();
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    refused++;
                }
            }
            assertThat(created).isEqualTo(2);
            assertThat(refused).isEqualTo(6);
            assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- privacy -------------------------------------------------------------------------------------------------------

    @Test
    void aPresetIsPrivate_everyoneElseGetsTheSame404AsForAnUnknownId() throws Exception {
        UUID mine = UUID.fromString(createOk(alice, "Private", "secret plans").path("presetId").asString());
        UUID unknown = UUID.randomUUID();

        UUID otherTenant = fx.tenant();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.role(otherTenant, "S", new String[]{"dsr.search-documents", "docm.documents.view", CREATE, UPDATE, DELETE}, stranger);

        // reads and runs: 404 for another person, an administrator, and a person from another organisation
        for (var jwt : List.of(fx.jwtFor(bob), fx.jwtFor(dave), fx.tenantAdminJwtFor(tenantAdmin), fx.jwtFor(stranger))) {
            JsonNode read = call(get(BASE + "/" + mine), jwt, null, 404);
            JsonNode run = call(post(BASE + "/" + mine + "/run"), jwt, null, 404);
            assertThat(read.toString() + run).doesNotContain("secret plans").doesNotContain("Private");
            assertThat(call(get(BASE), jwt, null, 200).path("count").asInt()).isZero();
        }
        // writes by people who hold the codes: also 404, and nothing changes
        var strangerJwt = fx.jwtFor(stranger);
        call(put(BASE + "/" + mine), strangerJwt, Map.of("name", "stolen"), 404);
        call(delete(BASE + "/" + mine), strangerJwt, null, 404);
        var adminJwt = fx.tenantAdminJwtFor(tenantAdmin);
        call(put(BASE + "/" + mine), adminJwt, Map.of("name", "stolen"), 404);
        call(delete(BASE + "/" + mine), adminJwt, null, 404);

        // the same answer for someone else's id and for an id that never existed
        String volatileFields = "\"(timestamp|traceId|requestId)\":\"[^\"]*\"";
        String known = call(get(BASE + "/" + mine), fx.jwtFor(bob), null, 404).toString().replace(mine.toString(), "ID");
        String missing = call(get(BASE + "/" + unknown), fx.jwtFor(bob), null, 404).toString().replace(unknown.toString(), "ID");
        assertThat(known.replaceAll(volatileFields, "")).isEqualTo(missing.replaceAll(volatileFields, ""));

        assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isEqualTo(1);
        JsonNode stored = call(get(BASE + "/" + mine), fx.jwtFor(alice), null, 200);
        assertThat(stored.path("criteria").path("q").asString()).isEqualTo("secret plans");
        assertThat(stored.path("name").asString()).isEqualTo("Private");
    }

    // ---- outdated filter values ------------------------------------------------------------------------------------------

    @Test
    void runningDropsOutdatedFilterValues_namesThem_andLeavesThePresetAlone() throws Exception {
        Department department = fx.department(tenantId, "Finance");
        UUID folder = folder(null, "Reports");
        UUID field = metadataField("invoice_no", "text", true);
        doc("Zebra one", "z1.txt", "zebra");
        index();
        UUID id = UUID.fromString(call(post(BASE), fx.jwtFor(alice), Map.of("name", "Everything", "criteria", Map.of(
                "q", "zebra", "departmentId", department.getDepartmentId().toString(), "folderId", folder.toString(),
                "meta", List.of(Map.of("fieldId", field.toString(), "op", "eq", "value", "INV-1")))), 201).path("presetId").asString());

        fx.jdbc().update("delete from departments where department_id = ?", department.getDepartmentId());
        fx.jdbc().update("delete from folders where folder_id = ?", folder);
        fx.jdbc().update("update metadata_fields set status = 'inactive' where field_id = ?", field);

        JsonNode run = call(post(BASE + "/" + id + "/run"), fx.jwtFor(alice), null, 200);
        assertThat(run.path("warnings").size()).isEqualTo(1);
        JsonNode warning = run.path("warnings").get(0);
        assertThat(warning.path("code").asString()).isEqualTo("FILTER_NO_LONGER_EXISTS");
        List<String> named = new ArrayList<>();
        warning.path("details").forEach(d -> named.add(d.asString()));
        assertThat(named).contains("department", "folder", "metadata");
        JsonNode resolved = run.path("criteria");
        assertThat(resolved.path("q").asString()).isEqualTo("zebra");
        assertThat(resolved.has("departmentId")).isFalse();
        assertThat(resolved.has("folderId")).isFalse();
        assertThat(resolved.has("meta")).isFalse();
        assertThat(titles(run.path("result"))).containsExactly("Zebra one");
        // the stored preset is untouched
        JsonNode stored = call(get(BASE + "/" + id), fx.jwtFor(alice), null, 200).path("criteria");
        assertThat(stored.has("departmentId")).isTrue();
        assertThat(stored.has("meta")).isTrue();
    }

    // ---- archived ----------------------------------------------------------------------------------------------------------

    @Test
    void archivedStatesNeedTheArchivePermission_atSaveAndAtRun() throws Exception {
        UUID live = doc("Zebra live", "l.txt", "zebra");
        UUID old = doc("Zebra archived", "a.txt", "zebra");
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", old);
        index();

        create(alice, "Wants archive", Map.of("q", "zebra", "statuses", List.of("archived")), 403);
        JsonNode saved = create(archivist, "Archive", Map.of("q", "zebra", "statuses", List.of("archived")), 201);
        JsonNode asArchivist = call(post(BASE + "/" + saved.path("presetId").asString() + "/run"), fx.jwtFor(archivist), null, 200);
        assertThat(titles(asArchivist.path("result"))).containsExactly("Zebra archived");

        // a person who lost the permission: the preset still runs, minus the archive, and says so
        fx.jdbc().update("insert into saved_searches (preset_id, tenant_id, user_id, name, criteria, schema_version) values (?, ?, ?, 'Old archive', ?::jsonb, 1)",
                UUID.randomUUID(), tenantId, alice.getUserId(), "{\"schemaVersion\":1,\"q\":\"zebra\",\"statuses\":[\"active\",\"archived\"]}");
        UUID stale = fx.jdbc().queryForObject("select preset_id from saved_searches where name = 'Old archive' and tenant_id = ?", UUID.class, tenantId);
        JsonNode run = call(post(BASE + "/" + stale + "/run"), fx.jwtFor(alice), null, 200);
        assertThat(run.path("warnings").get(0).path("code").asString()).isEqualTo("ARCHIVE_NOT_ALLOWED");
        assertThat(titles(run.path("result"))).containsExactly("Zebra live");
        assertThat(live).isNotNull();
    }

    // ---- versions -----------------------------------------------------------------------------------------------------------

    @Test
    void aPresetFromANewerVersionIsListedButCannotBeRun_andOldOnesStillWork() throws Exception {
        UUID future = UUID.randomUUID();
        fx.jdbc().update("insert into saved_searches (preset_id, tenant_id, user_id, name, criteria, schema_version) values (?, ?, ?, 'From the future', ?::jsonb, 99)",
                future, tenantId, alice.getUserId(), "{\"schemaVersion\":99,\"query\":\"x\"}");
        JsonNode list = call(get(BASE), fx.jwtFor(alice), null, 200);
        assertThat(list.path("items").get(0).path("runnable").asBoolean()).isFalse();
        assertThat(call(post(BASE + "/" + future + "/run"), fx.jwtFor(alice), null, 409).path("code").asString())
                .isEqualTo("PRESET_VERSION_UNSUPPORTED");
        assertThat(call(get(BASE + "/" + future), fx.jwtFor(alice), null, 200).path("runnable").asBoolean()).isFalse();

        // a stored version 1 preset (the fixture shape, kept for good) runs unchanged
        UUID v1 = UUID.randomUUID();
        fx.jdbc().update("insert into saved_searches (preset_id, tenant_id, user_id, name, criteria, schema_version) values (?, ?, ?, 'V1 fixture', ?::jsonb, 1)",
                v1, tenantId, alice.getUserId(), "{\"q\":\"anything\",\"statuses\":[\"active\"],\"sort\":\"title\",\"ascending\":true}");
        JsonNode run = call(post(BASE + "/" + v1 + "/run"), fx.jwtFor(alice), null, 200);
        assertThat(run.path("criteria").path("q").asString()).isEqualTo("anything");
        assertThat(run.path("criteria").path("sort").asString()).isEqualTo("title");
    }

    // ---- permissions ---------------------------------------------------------------------------------------------------------

    @Test
    void thePermissionMatrix() throws Exception {
        // nobody without dsr.search-documents: carol holds only docm.documents.view
        call(get(BASE), fx.jwtFor(carol), null, 403);
        create(carol, "x", Map.of("q", "x"), 403);
        // search only: may read and run, may not change
        UUID mine = UUID.fromString(createOk(alice, "Mine", "x").path("presetId").asString());
        call(get(BASE), fx.jwtFor(dave), null, 200);
        create(dave, "x", Map.of("q", "x"), 403);
        call(put(BASE + "/" + mine), fx.jwtFor(dave), Map.of("name", "y"), 403);
        call(delete(BASE + "/" + mine), fx.jwtFor(dave), null, 403);
        // create only
        create(erin, "E", Map.of("q", "x"), 201);
        UUID erins = fx.jdbc().queryForObject("select preset_id from saved_searches where user_id = ? and tenant_id = ?", UUID.class, erin.getUserId(), tenantId);
        call(put(BASE + "/" + erins), fx.jwtFor(erin), Map.of("name", "E2"), 403);
        call(delete(BASE + "/" + erins), fx.jwtFor(erin), null, 403);
        // update and delete holders
        call(put(BASE + "/" + mine), fx.jwtFor(alice), Map.of("name", "Renamed"), 200);
        call(delete(BASE + "/" + mine), fx.jwtFor(alice), null, 204);
        call(delete(BASE + "/" + mine), fx.jwtFor(alice), null, 404);
        // the admin limit endpoints are tenant-admin only
        call(get("/api/v1/admin/search/presets/limit"), fx.jwtFor(alice), null, 403);
        // no login at all
        mvc.perform(get(BASE)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ---- audit and logs -----------------------------------------------------------------------------------------------------------

    @Test
    void createUpdateAndDeleteAreAudited_withFieldNamesNeverValues() throws Exception {
        UUID id = UUID.fromString(createOk(alice, "Audited name", "confidential-keyword-4711").path("presetId").asString());
        call(put(BASE + "/" + id), fx.jwtFor(alice), Map.of("criteria", Map.of("q", "other-keyword-9", "createdFrom", "2026-01-01T00:00:00Z")), 200);
        call(delete(BASE + "/" + id), fx.jwtFor(alice), null, 204);
        for (String action : List.of("SEARCH_PRESET_CREATED", "SEARCH_PRESET_UPDATED", "SEARCH_PRESET_DELETED")) {
            assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId, action))
                    .as(action).isEqualTo(1);
        }
        String all = String.join(" ", fx.jdbc().queryForList(
                "select coalesce(new_value::text,'') || coalesce(previous_value::text,'') || coalesce(resource_name,'') from audit_events"
                        + " where tenant_id = ? and action like 'SEARCH_PRESET%'", String.class, tenantId));
        assertThat(all).doesNotContain("confidential-keyword-4711").doesNotContain("other-keyword-9").doesNotContain("Audited name");
        assertThat(all).contains("q").contains("createdFrom");
    }

    @Test
    void noLogLineCarriesTheKeywordsOrNames() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            UUID id = UUID.fromString(createOk(alice, "Name-xyzzy-77", "keyword-xyzzy-77").path("presetId").asString());
            call(post(BASE + "/" + id + "/run"), fx.jwtFor(alice), null, 200);
            call(put(BASE + "/" + id), fx.jwtFor(alice), Map.of("name", "Name-xyzzy-78"), 200);
            call(delete(BASE + "/" + id), fx.jwtFor(alice), null, 204);
            create(alice, "Empty-xyzzy-77", Map.of("sort", "title"), 400);
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
        List<String> offenders = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("xyzzy")).toList();
        assertThat(offenders).isEmpty();
    }
}
