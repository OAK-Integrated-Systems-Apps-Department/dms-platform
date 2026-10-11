package com.oak.dms.importexport.export;

import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportMetadataAssemblerTest {

    private final DocumentMetadataRepository documentMetadataRepository = mock(DocumentMetadataRepository.class);
    private final ImportSchemaFields importSchemaFields = mock(ImportSchemaFields.class);
    private final MetadataFieldOptionRepository optionRepository = mock(MetadataFieldOptionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ExportMetadataAssembler assembler = new ExportMetadataAssembler(documentMetadataRepository,
            importSchemaFields, optionRepository, userRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invoiceType = UUID.randomUUID();
    private final UUID contractType = UUID.randomUUID();

    private final ImportField amount = field("amount", "Amount", "decimal", false);
    private final ImportField status = field("status", "Status", "dropdown", false);
    private final ImportField tags = field("tags", "Tags", "multi_select", false);
    private final ImportField due = field("due", "Due date", "date", false);
    private final ImportField internal = field("internal", "Internal note", "text", true);
    private final ImportField owner = field("owner", "Owner", "user_ref", false);
    private final ImportField signed = field("signed", "Signed on", "datetime", false);

    @Test
    void build_fixedColumnsThenTheUnionOfVisibleFields_blankWhereAFieldDoesNotApply() {
        UUID ownerId = UUID.randomUUID();
        ExportedDocument invoice = document("invoice.pdf", "clean");
        ExportedDocument contract = document("contract.docx", "overridden");
        when(importSchemaFields.load(tenantId, invoiceType, 1)).thenReturn(fields(amount, status, tags, internal, owner));
        when(importSchemaFields.load(tenantId, contractType, 2)).thenReturn(fields(due, signed, owner));
        when(documentMetadataRepository.findAllById(anyIterable())).thenReturn(List.of(
                metadata(invoice, invoiceType, 1, Map.of(
                        amount.fieldId().toString(), value(new BigDecimal("12.5")),
                        status.fieldId().toString(), value("PAID"),
                        tags.fieldId().toString(), value(List.of("URGENT", "RETIRED")),
                        internal.fieldId().toString(), value("do not share"),
                        owner.fieldId().toString(), value(ownerId.toString()))),
                metadata(contract, contractType, 2, Map.of(
                        due.fieldId().toString(), value("2026-04-30"),
                        signed.fieldId().toString(), value("2026-04-01T08:15:00")))));
        when(optionRepository.findAllByFieldOrdered(status.fieldId(), tenantId))
                .thenReturn(List.of(option("PAID", "Paid in full")));
        when(optionRepository.findAllByFieldOrdered(tags.fieldId(), tenantId))
                .thenReturn(List.of(option("URGENT", "Urgent"), option("RETIRED", "Retired option")));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                User.builder().userId(ownerId).tenantId(tenantId).displayName("Asha Perera").build()));

        ExportMetadataTable table = assembler.build(tenantId, List.of(invoice, contract));

        assertThat(table.headers()).containsExactly("File in ZIP", "Title", "Document type", "Uploaded by",
                "Uploaded at (UTC)", "Version", "Size (bytes)", "Scan status",
                "Amount", "Status", "Tags", "Owner", "Due date", "Signed on");
        assertThat(table.fixedColumns()).isEqualTo(8);

        List<ExportCell> invoiceRow = table.rows().get(0);
        assertThat(invoiceRow.get(0)).isEqualTo(new ExportCell.Text("invoice.pdf"));
        assertThat(invoiceRow.get(4)).isEqualTo(new ExportCell.DateTime(LocalDateTime.of(2026, 3, 1, 4, 30),
                ZoneOffset.UTC));
        assertThat(invoiceRow.get(7)).isEqualTo(new ExportCell.Text("Clean"));
        assertThat(invoiceRow.get(8)).isEqualTo(new ExportCell.Number(new BigDecimal("12.5")));
        assertThat(invoiceRow.get(9)).isEqualTo(new ExportCell.Options(List.of("PAID"), List.of("Paid in full")));
        // Inactive options still get their label.
        assertThat(invoiceRow.get(10)).isEqualTo(new ExportCell.Options(List.of("URGENT", "RETIRED"),
                List.of("Urgent", "Retired option")));
        assertThat(invoiceRow.get(11)).isEqualTo(new ExportCell.UserRef(ownerId.toString(), "Asha Perera"));
        assertThat(invoiceRow.get(12)).isEqualTo(ExportCell.BLANK);
        assertThat(invoiceRow.get(13)).isEqualTo(ExportCell.BLANK);

        List<ExportCell> contractRow = table.rows().get(1);
        assertThat(contractRow.get(7)).isEqualTo(new ExportCell.Text("Released by administrator"));
        assertThat(contractRow.get(8)).isEqualTo(ExportCell.BLANK);
        assertThat(contractRow.get(11)).isEqualTo(ExportCell.BLANK);
        assertThat(contractRow.get(12)).isEqualTo(new ExportCell.Date(LocalDate.of(2026, 4, 30)));
        assertThat(contractRow.get(13)).isEqualTo(new ExportCell.DateTime(LocalDateTime.of(2026, 4, 1, 8, 15), null));
    }

    @Test
    void build_documentWithoutMetadata_getsOnlyTheFixedColumns() {
        ExportedDocument plain = document("plain.txt", "clean");
        when(documentMetadataRepository.findAllById(anyIterable())).thenReturn(List.of());

        ExportMetadataTable table = assembler.build(tenantId, List.of(plain));

        assertThat(table.headers()).hasSize(8);
        assertThat(table.rows().get(0)).hasSize(8);
    }

    @Test
    void cell_valueThatDoesNotFitItsType_isKeptAsText() {
        assertThat(ExportMetadataAssembler.cell(amount, "about ten", Map.of(), Map.of()))
                .isEqualTo(new ExportCell.Text("about ten"));
        assertThat(ExportMetadataAssembler.cell(due, "31/04/2026", Map.of(), Map.of()))
                .isEqualTo(new ExportCell.Text("31/04/2026"));
        assertThat(ExportMetadataAssembler.cell(amount, null, Map.of(), Map.of())).isEqualTo(ExportCell.BLANK);
    }

    @Test
    void cell_unknownOptionCode_fallsBackToTheCode() {
        assertThat(ExportMetadataAssembler.cell(status, "GONE", Map.of(), Map.of()))
                .isEqualTo(new ExportCell.Options(List.of("GONE"), List.of("GONE")));
    }

    private ImportField field(String key, String label, String dataType, boolean hidden) {
        return new ImportField(UUID.randomUUID(), key, label, dataType, false, hidden, null);
    }

    private static Map<String, ImportField> fields(ImportField... fields) {
        Map<String, ImportField> map = new LinkedHashMap<>();
        for (ImportField field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }

    private ExportedDocument document(String filename, String scanStatus) {
        ExportableDocumentRow row = new ExportableDocumentRow(UUID.randomUUID(), tenantId, filename, null, "Type",
                UUID.randomUUID(), "Uploader", OffsetDateTime.parse("2026-03-01T10:00:00+05:30"), false,
                UUID.randomUUID(), 1, filename, 100L, scanStatus, false);
        return new ExportedDocument(row, filename);
    }

    private DocumentMetadata metadata(ExportedDocument document, UUID typeId, int version,
                                      Map<String, FieldValue> values) {
        return DocumentMetadata.builder()
                .id(new DocumentMetadataId(tenantId, document.row().documentId()))
                .documentTypeId(typeId)
                .schemaVersion(version)
                .fieldValues(new LinkedHashMap<>(values))
                .build();
    }

    private static FieldValue value(Object v) {
        return new FieldValue(v, "import", null);
    }

    private static MetadataFieldOption option(String code, String label) {
        return MetadataFieldOption.builder().optionValue(code).optionLabel(label).build();
    }
}
