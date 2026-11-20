package com.oak.dms.ocrworker;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.folder.DocumentAccessEntry;
import com.oak.dms.folder.DocumentAccessRepository;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessEntry;
import com.oak.dms.folder.FolderAccessEntry.PrincipalType;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessRepository;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentType.DocumentTypeStatus;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOC-01: {@code GET/PUT /documents/{id}/metadata} and {@code GET /documents/{id}/ocr-status} used to check only the
 * tenant, so anyone holding the plain permission code could read - and, with {@code os.ocr.edit-text}, overwrite - a
 * document that folder or per-document access hides from them. They now apply the same rule as every other document
 * route: hidden (or deleted, or another tenant's) is 404, visible but not changeable is 403 on a write.
 * <p>
 * The real {@code DocumentAccessService}, folder access and database are used - only the permission-code gate
 * ({@code @authz.hasPermission}) is stubbed as granted, because that annotation is a separate layer and this class is
 * about the access check behind it. Each test seeds its own tenant, so the access cache never carries state between
 * tests. Lives in this package only to reuse the package-private {@code ScanGateFixture}.
 */
@SpringBootTest
class DocumentMetadataAccessIT {

    @Autowired private WebApplicationContext context;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentTypeRepository documentTypeRepository;
    @Autowired private MetadataFieldRepository metadataFieldRepository;
    @Autowired private DocumentTypeSchemaRepository documentTypeSchemaRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private DocumentOcrFieldResultRepository ocrResults;
    @Autowired private DocumentAccessRepository documentAccessRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FolderAccessRepository folderAccessRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    /** Permission codes are assumed held (a role grants them); {@code isTenantAdmin()} stays false unless a test says so. */
    @MockitoBean private AuthorizationService authorizationService;

    private MockMvc mvc;
    private UUID tenantId;
    private DocumentType type;
    private final UUID ownerKc = UUID.randomUUID();
    private User owner;

    @BeforeEach
    void seedTenant() {
        when(authorizationService.hasPermission(anyString())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tenantId = newTenant();
        owner = newUser(tenantId, ownerKc);
        type = documentTypeRepository.save(DocumentType.builder().tenantId(tenantId).typeCode("GEN")
                .typeName("General").status(DocumentTypeStatus.active).build());
        MetadataField field = metadataFieldRepository.save(MetadataField.builder().tenantId(tenantId)
                .fieldKey("invoice_number").label("Invoice").dataType("text").build());
        documentTypeSchemaRepository.saveAndFlush(DocumentTypeSchema.builder().tenantId(tenantId)
                .documentTypeId(type.getDocumentTypeId()).schemaVersion(type.getCurrentSchemaVersion())
                .fields(List.of(new DocumentTypeSchema.FieldMapping(UUID.randomUUID(), field.getFieldId(),
                        false, false, false, 10, null, null)))
                .publishedBy(owner.getUserId()).build());
    }

    // ---------------------------------------------------------------- fixtures

    private UUID newTenant() {
        return tenantRepository.save(Tenant.builder().tenantName("doc01 IT " + UUID.randomUUID())
                .domain("doc01-it-" + UUID.randomUUID() + ".invalid").subscriptionTier("standard")
                .status(Tenant.TenantStatus.active).build()).getTenantId();
    }

    private User newUser(UUID tenant, UUID keycloakId) {
        return userRepository.save(User.builder().tenantId(tenant).keycloakId(keycloakId)
                .email(keycloakId + "@doc01-it.invalid").username(keycloakId.toString()).firstName("Doc01")
                .lastName("IT").displayName("Doc01 IT").status(User.UserStatus.active).build());
    }

    /** A live document of the tenant, in the given folder (or at top level) with the given access mode. */
    private UUID newDocument(FolderAccessMode mode, UUID folderId) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID locationId = ScanGateFixture.documentLocation(jdbc, tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            ScanGateFixture.cleanScanResult(jdbc, tenantId, versionId, type.getDocumentTypeId(), owner.getUserId(),
                    "doc01.pdf", "application/pdf", "deadbeef");
            documentRepository.save(Document.builder().documentId(documentId).tenantId(tenantId)
                    .documentTypeId(type.getDocumentTypeId()).title("doc01.pdf").latestVersionId(versionId)
                    .currentVersionId(versionId).ownerUserId(owner.getUserId()).folderId(folderId)
                    .accessMode(mode).build());
            documentVersionRepository.save(DocumentVersion.builder().versionId(versionId).documentId(documentId)
                    .tenantId(tenantId).versionNumber(1).majorVersion(1).minorVersion(0)
                    .storageObjectKey("tenants/%s/documents/%s/versions/%s/x.pdf".formatted(tenantId, documentId, versionId))
                    .originalFilename("doc01.pdf").mimeType("application/pdf").fileHash("deadbeef").sizeBytes(1)
                    .createdReason("initial_upload").storageLocationId(locationId).scanStatus("clean")
                    .ocrStatus("completed").requestedFields("[]").build());
        });
        ocrResults.save(DocumentOcrFieldResult.builder().fieldResultId(UUID.randomUUID()).tenantId(tenantId)
                .documentId(documentId).versionId(versionId).fieldKey("invoice_number").value("OCR-VALUE")
                .confidence(BigDecimal.valueOf(0.9)).status("extracted").createdAt(OffsetDateTime.now()).build());
        return documentId;
    }

    /** A document restricted to its own list, with the given users on it. */
    private UUID restrictedDocument(Grant... grants) {
        UUID documentId = newDocument(FolderAccessMode.restricted, null);
        for (Grant g : grants) {
            documentAccessRepository.save(DocumentAccessEntry.builder().tenantId(tenantId).documentId(documentId)
                    .principalType(PrincipalType.user).principalId(g.user.getUserId()).accessLevel(g.level).build());
        }
        return documentId;
    }

    private record Grant(User user, FolderAccessLevel level) { }

    private static Grant grant(User user, FolderAccessLevel level) {
        return new Grant(user, level);
    }

    /** A restricted folder only {@code member} is listed on, holding one inherit-mode document. */
    private UUID documentInRestrictedFolder(User member, FolderAccessLevel level) {
        Folder folder = folderRepository.save(Folder.builder().tenantId(tenantId).folderName("Locked " + UUID.randomUUID())
                .accessMode(FolderAccessMode.restricted).build());
        folderAccessRepository.save(FolderAccessEntry.builder().tenantId(tenantId).folderId(folder.getFolderId())
                .principalType(PrincipalType.user).principalId(member.getUserId()).accessLevel(level).build());
        return newDocument(FolderAccessMode.inherit, folder.getFolderId());
    }

    private MockHttpServletRequestBuilder as(UUID keycloakId, UUID tenant, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", tenant.toString()))
                .authorities());
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return as(user.getKeycloakId(), user.getTenantId(), request);
    }

    private static MockHttpServletRequestBuilder getMetadata(UUID id) {
        return get("/api/v1/documents/" + id + "/metadata");
    }

    private static MockHttpServletRequestBuilder getOcrStatus(UUID id) {
        return get("/api/v1/documents/" + id + "/ocr-status");
    }

    private static MockHttpServletRequestBuilder putMetadata(UUID id, String value) {
        return put("/api/v1/documents/" + id + "/metadata").contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoice_number\":\"" + value + "\"}");
    }

    /** What is stored for the document, read straight from the table - not through the endpoint under test. */
    private String stored(UUID documentId) {
        List<String> rows = jdbc.queryForList(
                "select field_values::text from document_metadata where document_id = ?", String.class, documentId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    // ------------------------------------------------------------------- tests

    @Test
    void aRestrictedDocumentTheCallerIsNotOn_isNotFound_onAllThree_andNothingIsWritten() throws Exception {
        User outsider = newUser(tenantId, UUID.randomUUID());
        UUID doc = restrictedDocument(grant(owner, FolderAccessLevel.manage));
        mvc.perform(as(owner, putMetadata(doc, "ORIGINAL"))).andExpect(status().isOk());

        mvc.perform(as(outsider, getMetadata(doc))).andExpect(status().isNotFound());
        mvc.perform(as(outsider, getOcrStatus(doc))).andExpect(status().isNotFound());
        mvc.perform(as(outsider, putMetadata(doc, "TAMPERED"))).andExpect(status().isNotFound());

        assertThat(stored(doc)).contains("ORIGINAL").doesNotContain("TAMPERED");
    }

    @Test
    void aViewOnlyUser_canRead_butNotWrite() throws Exception {
        User viewer = newUser(tenantId, UUID.randomUUID());
        UUID doc = restrictedDocument(grant(owner, FolderAccessLevel.manage), grant(viewer, FolderAccessLevel.view));
        mvc.perform(as(owner, putMetadata(doc, "ORIGINAL"))).andExpect(status().isOk());

        mvc.perform(as(viewer, getMetadata(doc))).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_number").value("ORIGINAL"));
        mvc.perform(as(viewer, getOcrStatus(doc))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
        mvc.perform(as(viewer, putMetadata(doc, "TAMPERED"))).andExpect(status().isForbidden());

        assertThat(stored(doc)).contains("ORIGINAL").doesNotContain("TAMPERED");
    }

    @Test
    void anEditor_canWrite() throws Exception {
        User editor = newUser(tenantId, UUID.randomUUID());
        UUID doc = restrictedDocument(grant(editor, FolderAccessLevel.edit));

        mvc.perform(as(editor, putMetadata(doc, "EDITED"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_number").value("EDITED"));
        mvc.perform(as(editor, getMetadata(doc))).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_number").value("EDITED"));
        assertThat(stored(doc)).contains("EDITED");
    }

    @Test
    void aDocumentInAFolderThatHidesItFromTheCaller_isNotFound_andAFolderViewerMayReadOnly() throws Exception {
        User member = newUser(tenantId, UUID.randomUUID());
        User outsider = newUser(tenantId, UUID.randomUUID());
        UUID doc = documentInRestrictedFolder(member, FolderAccessLevel.view);

        mvc.perform(as(outsider, getMetadata(doc))).andExpect(status().isNotFound());
        mvc.perform(as(outsider, getOcrStatus(doc))).andExpect(status().isNotFound());
        mvc.perform(as(outsider, putMetadata(doc, "TAMPERED"))).andExpect(status().isNotFound());

        mvc.perform(as(member, getMetadata(doc))).andExpect(status().isOk());
        mvc.perform(as(member, getOcrStatus(doc))).andExpect(status().isOk());
        mvc.perform(as(member, putMetadata(doc, "TAMPERED"))).andExpect(status().isForbidden());
        assertThat(stored(doc)).doesNotContain("TAMPERED");
    }

    @Test
    void anotherTenantsUser_getsNotFound_onAllThree() throws Exception {
        UUID doc = newDocument(FolderAccessMode.inherit, null);
        UUID otherTenant = newTenant();
        User foreigner = newUser(otherTenant, UUID.randomUUID());

        mvc.perform(as(foreigner, getMetadata(doc))).andExpect(status().isNotFound());
        mvc.perform(as(foreigner, getOcrStatus(doc))).andExpect(status().isNotFound());
        mvc.perform(as(foreigner, putMetadata(doc, "TAMPERED"))).andExpect(status().isNotFound());
        assertThat(stored(doc)).doesNotContain("TAMPERED");
    }

    @Test
    void aSoftDeletedDocument_isNotFound_onAllThree_evenForItsOwner() throws Exception {
        UUID doc = newDocument(FolderAccessMode.inherit, null);
        jdbc.update("update documents set is_deleted = true where document_id = ?", doc);

        mvc.perform(as(owner, getMetadata(doc))).andExpect(status().isNotFound());
        mvc.perform(as(owner, getOcrStatus(doc))).andExpect(status().isNotFound());
        mvc.perform(as(owner, putMetadata(doc, "X"))).andExpect(status().isNotFound());
    }

    @Test
    void anOrdinaryDocumentAtTopLevel_keepsWorkingForOrdinaryUsers() throws Exception {
        User user = newUser(tenantId, UUID.randomUUID());
        UUID doc = newDocument(FolderAccessMode.inherit, null);

        mvc.perform(as(user, putMetadata(doc, "NORMAL"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_number").value("NORMAL"));
        mvc.perform(as(user, getMetadata(doc))).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice_number").value("NORMAL"));
        mvc.perform(as(user, getOcrStatus(doc))).andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.invoice_number.value").value("OCR-VALUE"));
    }

    @Test
    void aTenantAdministrator_keepsTheAccessTheModelGivesThem() throws Exception {
        User admin = newUser(tenantId, UUID.randomUUID());
        UUID doc = restrictedDocument(grant(owner, FolderAccessLevel.manage)); // the admin is on no list
        when(authorizationService.isTenantAdmin()).thenReturn(true);

        mvc.perform(as(admin, putMetadata(doc, "BY-ADMIN"))).andExpect(status().isOk());
        mvc.perform(as(admin, getMetadata(doc))).andExpect(status().isOk());
        mvc.perform(as(admin, getOcrStatus(doc))).andExpect(status().isOk());
    }
}
