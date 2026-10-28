package com.oak.dms.importexport.service;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.dto.ImportTemplateRequest;
import com.oak.dms.importexport.dto.ImportTemplateResponse;
import com.oak.dms.importexport.entity.ImportTemplate;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.repository.ImportTemplateRepository;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateLayout;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saved templates: what may be saved, and how a template meets the current schema on download and upload. */
class ImportTemplateServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID CURRENCY_ID = UUID.randomUUID();

    private final ImportTemplateRepository repository = mock(ImportTemplateRepository.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentTypeFieldService schemas = mock(DocumentTypeFieldService.class);
    private final MetadataValidationService validation = mock(MetadataValidationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ImportTemplateService service = new ImportTemplateService(repository, types, schemas, validation,
            new ImportValueConverter(), new ImportUsers(mock(UserRepository.class)), audit);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, UUID.randomUUID());
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(types.findByDocumentTypeIdAndTenantIdAndDeletedFalse(TYPE, TENANT)).thenReturn(Optional.of(
                DocumentType.builder().documentTypeId(TYPE).tenantId(TENANT).typeName("Invoice").typeCode("INV")
                        .status(DocumentType.DocumentTypeStatus.active).build()));
        when(schemas.getSchema(TYPE)).thenReturn(schema(
                field("invoice_number", "Invoice Number", "text", true, 10),
                field("amount", "Amount", "decimal", true, 20),
                new SchemaField(CURRENCY_ID, "currency", "Currency", null, "dropdown", "active", true, false, false, 30,
                        null, null, List.of(new MetadataFieldOptionResponse(UUID.randomUUID(), CURRENCY_ID, null, "LKR",
                        "LKR", 1, "active")), List.of()),
                field("notes", "Notes", "text", false, 40),
                field("internal", "Internal", "text", false, 50, true)));
        when(validation.validate(eq(TYPE), any(), anyMap())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> {
            ImportTemplate t = call.getArgument(0);
            if (t.getImportTemplateId() == null) {
                t.setImportTemplateId(UUID.randomUUID());
            }
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void create_savesColumnsInOrderWithHeaders_andDefaultsForTheRest() {
        ImportTemplateResponse saved = service.create(request("Finance",
                List.of(column("amount", "Total"), column("invoice_number", " ")), Map.of("currency", " LKR ")));

        assertThat(saved.name()).isEqualTo("Finance");
        assertThat(saved.columns()).containsExactly(column("amount", "Total"), column("invoice_number", null));
        assertThat(saved.defaults()).containsExactly(Map.entry("currency", "LKR"));
        assertThat(saved.documentTypeName()).isEqualTo("Invoice");
        verify(audit).event("IMPORT_TEMPLATE_CREATED");
    }

    @Test
    void create_refusesEverythingThatWouldMakeABrokenTemplate_listingEachProblem() {
        when(validation.validate(eq(TYPE), any(), argThat(m -> "USD".equals(m.get("currency")))))
                .thenReturn(List.of(new ValidationError("currency", "VALUE_NOT_IN_OPTIONS", "Not an option.", "error")));

        assertThatThrownBy(() -> service.create(request("Broken",
                List.of(column("amount", "Notes"), column("notes", null), column("ghost", null), column("internal", null)),
                Map.of("currency", "USD"))))
                .isInstanceOf(BulkImportException.class)
                .hasMessageContaining("\"ghost\" isn't a field")
                .hasMessageContaining("\"internal\" isn't a field") // hidden fields can't be columns
                .hasMessageContaining("Two columns are called \"Notes\"")
                .hasMessageContaining("Default for Currency: Not an option.")
                .hasMessageContaining("Required fields need a column or a default value: Invoice Number, Currency.");

        assertThatThrownBy(() -> service.create(request("Bad default", List.of(column("invoice_number", null)),
                Map.of("amount", "abc", "currency", "LKR"))))
                .isInstanceOf(BulkImportException.class)
                .hasMessageContaining("Default for Amount: Amount must be a number");
        verify(repository, never()).save(any());
    }

    @Test
    void create_nameMustBeFreeForTheType() {
        when(repository.existsByTenantIdAndDocumentTypeIdAndNameIgnoreCaseAndDeletedFalse(TENANT, TYPE, "Finance"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Finance", List.of(column("invoice_number", null),
                column("amount", null)), Map.of("currency", "LKR"))))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("already has a template called");
    }

    @Test
    void layout_followsTheCurrentSchema_droppingRemovedFields_addingUncoveredRequiredOnes() {
        ImportTemplate saved = ImportTemplate.builder().importTemplateId(UUID.randomUUID()).tenantId(TENANT)
                .documentTypeId(TYPE).name("Old")
                .columns(List.of(new ImportTemplate.Column("notes", "Remarks"), new ImportTemplate.Column("removed", null),
                        new ImportTemplate.Column("amount", null)))
                .defaults(Map.of("currency", "LKR"))
                .build();

        TemplateLayout layout = service.layout(saved, schemas.getSchema(TYPE));

        assertThat(layout.importTemplateId()).isEqualTo(saved.getImportTemplateId());
        // invoice_number became required with no column/default -> appended; currency has a default -> not.
        assertThat(layout.fields()).extracting(SchemaField::fieldKey).containsExactly("notes", "amount", "invoice_number");
        assertThat(layout.header(layout.fields().get(0))).isEqualTo("Remarks");
        assertThat(layout.header(layout.fields().get(1))).isEqualTo("Amount");
    }

    @Test
    void defaultsForUpload_templateGoneOrForAnotherType_isRefused() {
        UUID id = UUID.randomUUID();
        when(repository.findByImportTemplateIdAndTenantIdAndDeletedFalse(id, TENANT)).thenReturn(Optional.of(
                ImportTemplate.builder().importTemplateId(id).documentTypeId(TYPE).defaults(Map.of("currency", "LKR")).build()));

        assertThat(service.defaultsForUpload(id, TYPE)).containsExactly(Map.entry("currency", "LKR"));
        assertThatThrownBy(() -> service.defaultsForUpload(id, UUID.randomUUID()))
                .isInstanceOf(InvalidImportTemplateException.class);
        assertThatThrownBy(() -> service.defaultsForUpload(UUID.randomUUID(), TYPE))
                .isInstanceOf(InvalidImportTemplateException.class).hasMessageContaining("no longer exists");
    }

    @Test
    void update_keepsTheType_andAuditsBeforeAndAfter() {
        UUID id = UUID.randomUUID();
        ImportTemplate existing = ImportTemplate.builder().importTemplateId(id).tenantId(TENANT).documentTypeId(TYPE)
                .name("Finance").columns(List.of(new ImportTemplate.Column("invoice_number", null),
                        new ImportTemplate.Column("amount", null))).defaults(Map.of("currency", "LKR")).build();
        when(repository.findByImportTemplateIdAndTenantIdAndDeletedFalse(id, TENANT)).thenReturn(Optional.of(existing));
        when(repository.existsByTenantIdAndDocumentTypeIdAndNameIgnoreCaseAndDeletedFalse(TENANT, TYPE, "finance"))
                .thenReturn(true); // its own name, in another case

        service.update(id, request("finance", List.of(column("invoice_number", "No"), column("amount", null)),
                Map.of("currency", "LKR")));

        ArgumentCaptor<ImportTemplate> saved = ArgumentCaptor.forClass(ImportTemplate.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getColumns()).first().isEqualTo(new ImportTemplate.Column("invoice_number", "No"));
        verify(audit).event("IMPORT_TEMPLATE_UPDATED");
        assertThatThrownBy(() -> service.update(id, new ImportTemplateRequest("x", null, UUID.randomUUID(), List.of(),
                Map.of())))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("can't be moved");
    }

    private static ImportTemplateRequest request(String name, List<ImportTemplateRequest.Column> columns,
                                                 Map<String, String> defaults) {
        return new ImportTemplateRequest(name, null, TYPE, columns, defaults);
    }

    private static ImportTemplateRequest.Column column(String key, String header) {
        return new ImportTemplateRequest.Column(key, header);
    }

    private static DocumentTypeSchemaResponse schema(SchemaField... fields) {
        return new DocumentTypeSchemaResponse(TYPE, "INV", "Invoice", 2, List.of(fields));
    }

    private static SchemaField field(String key, String label, String type, boolean mandatory, int order) {
        return field(key, label, type, mandatory, order, false);
    }

    private static SchemaField field(String key, String label, String type, boolean mandatory, int order, boolean hidden) {
        return new SchemaField(UUID.randomUUID(), key, label, null, type, "active", mandatory, hidden, false, order, null, null,
                List.of(), List.of());
    }
}
