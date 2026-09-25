package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.entity.MetadataFieldValidation;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.DocumentTypeService;
import com.oak.dms.metadata.service.MetadataFieldOptionService;
import com.oak.dms.metadata.service.MetadataFieldService;
import com.oak.dms.metadata.service.MetadataFieldValidationService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file guard for the document-type field mapping read/write/validate
 * responses: every payload the frontend consumes ({@code GET /fields},
 * {@code GET /fields?version=N}, {@code PUT /fields}, {@code GET /schema},
 * {@code POST /validate}) is serialized with the same {@link JsonMapper}
 * Spring MVC uses and compared against {@code snapshots/document-type-fields.json},
 * which was recorded from the pre-{@code document_type_schemas} implementation.
 * Generated ids become {@code <id-N>} (numbered by first appearance) and
 * timestamps become {@code <ts>}, so the comparison covers shape, field
 * names, ordering, nullability and values, but not random identifiers.
 * <p>
 * Re-record with {@code -Dsnapshot.record=true}.
 */
@SpringBootTest
class DocumentTypeFieldSnapshotIT {

    private static final Path SNAPSHOT = Path.of("src/test/resources/snapshots/document-type-fields.json");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*");

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private MetadataFieldOptionService metadataFieldOptionService;
    @Autowired
    private MetadataFieldValidationService metadataFieldValidationService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private MetadataValidationService metadataValidationService;
    @Autowired
    private MetadataFieldRepository metadataFieldRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Schema snapshot test tenant " + UUID.randomUUID())
                .domain("schema-snapshot-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@schema-snapshot-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());
        TenantContext.set(tenant.getTenantId(), admin.getUserId(), admin.getDisplayName(), keycloakId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void responsesMatchRecordedSnapshot() throws IOException {
        MetadataFieldResponse text = metadataFieldService.createField(
                new CreateMetadataFieldRequest("snap_text", "Snap Text", "help", "text", "dflt", true, false));
        metadataFieldValidationService.createValidation(text.fieldId(), new CreateMetadataFieldValidationRequest(
                MetadataFieldValidation.RuleType.max_length, "10", null, MetadataFieldValidation.Severity.error));
        metadataFieldValidationService.createValidation(text.fieldId(), new CreateMetadataFieldValidationRequest(
                MetadataFieldValidation.RuleType.regex, "^[A-Za-z]+$", "Letters only",
                MetadataFieldValidation.Severity.warning));

        MetadataFieldResponse dropdown = metadataFieldService.createField(
                new CreateMetadataFieldRequest("snap_dropdown", "Snap Dropdown", null, "dropdown", null, true, false));
        metadataFieldOptionService.createOption(dropdown.fieldId(),
                new CreateMetadataFieldOptionRequest("a", "Option A", null, 10));
        metadataFieldOptionService.createOption(dropdown.fieldId(),
                new CreateMetadataFieldOptionRequest("b", "Option B", null, 20));

        MetadataFieldResponse hidden = metadataFieldService.createField(
                new CreateMetadataFieldRequest("snap_hidden", "Snap Hidden", null, "text", null, true, false));
        MetadataFieldResponse gone = metadataFieldService.createField(
                new CreateMetadataFieldRequest("snap_gone", "Snap Gone", null, "text", null, true, false));
        MetadataFieldResponse number = metadataFieldService.createField(
                new CreateMetadataFieldRequest("snap_number", "Snap Number", null, "number", null, true, false));
        metadataFieldValidationService.createValidation(number.fieldId(), new CreateMetadataFieldValidationRequest(
                MetadataFieldValidation.RuleType.min_value, "100", null, MetadataFieldValidation.Severity.error));

        DocumentTypeResponse type = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest("SNAPSHOT_TYPE", "Snapshot Type", null, 0, false, false));
        UUID typeId = type.documentTypeId();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("neverPublished.fields", documentTypeFieldService.getCurrentFields(typeId));
        snapshot.put("neverPublished.schema", documentTypeFieldService.getSchema(typeId));

        snapshot.put("publishV2.response", documentTypeFieldService.replaceMapping(typeId,
                new ReplaceDocumentTypeFieldsRequest(List.of(
                        new DocumentTypeFieldMappingItem(text.fieldId(), false, false, 30, null, null),
                        new DocumentTypeFieldMappingItem(dropdown.fieldId(), true, false, 10, "Group One", "a"),
                        new DocumentTypeFieldMappingItem(hidden.fieldId(), true, true, 20, "Group One", null),
                        new DocumentTypeFieldMappingItem(gone.fieldId(), false, false, 40, null, null)))));

        // v3: text becomes newly mandatory, number is new and mandatory,
        // dropdown stays mandatory; the submitted orders are deliberately
        // gappy/unsorted to exercise the (index+1)*10 re-stamp.
        snapshot.put("publishV3.response", documentTypeFieldService.replaceMapping(typeId,
                new ReplaceDocumentTypeFieldsRequest(List.of(
                        new DocumentTypeFieldMappingItem(text.fieldId(), true, false, 5, "Group Two", "override"),
                        new DocumentTypeFieldMappingItem(dropdown.fieldId(), true, false, 1, "Group One", null),
                        new DocumentTypeFieldMappingItem(number.fieldId(), true, false, 99, null, null),
                        new DocumentTypeFieldMappingItem(hidden.fieldId(), true, true, 50, null, null),
                        new DocumentTypeFieldMappingItem(gone.fieldId(), false, false, 7, null, null)))));

        // A soft-deleted catalog field drops out of every read.
        MetadataField goneEntity = metadataFieldRepository.findById(gone.fieldId()).orElseThrow();
        goneEntity.setDeleted(true);
        metadataFieldRepository.save(goneEntity);

        snapshot.put("current.fields", documentTypeFieldService.getCurrentFields(typeId));
        snapshot.put("version1.fields", documentTypeFieldService.getFieldsAtVersion(typeId, 1));
        snapshot.put("version2.fields", documentTypeFieldService.getFieldsAtVersion(typeId, 2));
        snapshot.put("version3.fields", documentTypeFieldService.getFieldsAtVersion(typeId, 3));
        snapshot.put("version99.fields", documentTypeFieldService.getFieldsAtVersion(typeId, 99));
        snapshot.put("current.schema", documentTypeFieldService.getSchema(typeId));

        snapshot.put("validate.current.empty", metadataValidationService.validate(typeId, null, Map.of()));
        Map<String, Object> bad = new HashMap<>();
        bad.put("snap_text", "abc def ghi jkl");
        bad.put("snap_dropdown", "zzz");
        bad.put("snap_number", "5");
        bad.put("snap_hidden", "");
        snapshot.put("validate.current.bad", metadataValidationService.validate(typeId, null, bad));
        snapshot.put("validate.current.valid", metadataValidationService.validate(typeId, null,
                Map.of("snap_text", "Hello", "snap_dropdown", "b", "snap_number", 150)));
        snapshot.put("validate.v2.empty", metadataValidationService.validate(typeId, 2, Map.of()));
        snapshot.put("validate.v2.multi", metadataValidationService.validate(typeId, 2,
                Map.of("snap_dropdown", List.of("a", "nope"), "snap_text", "x".repeat(11))));
        snapshot.put("validate.unknownVersion", metadataValidationService.validate(typeId, 99, Map.of()));
        snapshot.put("validate.unknownTypeExplicitVersion",
                metadataValidationService.validate(UUID.randomUUID(), 1, Map.of("snap_text", "x")));

        String actual = jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(normalize(jsonMapper.valueToTree(snapshot), new HashMap<>())) + "\n";

        if (Boolean.getBoolean("snapshot.record")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual);
        }
        assertThat(actual).isEqualTo(Files.readString(SNAPSHOT));
    }

    private static JsonNode normalize(JsonNode node, Map<String, String> ids) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (node.isObject()) {
            ObjectNode copy = factory.objectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), normalize(e.getValue(), ids)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = factory.arrayNode();
            node.forEach(child -> copy.add(normalize(child, ids)));
            return copy;
        }
        if (node.isString()) {
            String value = node.asString();
            if (UUID_PATTERN.matcher(value).matches()) {
                return factory.stringNode(ids.computeIfAbsent(value, v -> "<id-" + (ids.size() + 1) + ">"));
            }
            if (TIMESTAMP_PATTERN.matcher(value).matches()) {
                return factory.stringNode("<ts>");
            }
        }
        return node;
    }
}
