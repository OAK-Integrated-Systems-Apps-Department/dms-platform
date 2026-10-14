package com.oak.dms.workflow.support;

import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionPromoter;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.repository.ScanEngineRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.entity.UserRole;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Builds the rows the workflow ITs need, each in its own fresh tenant (the
 * suite shares one database, like the rest of the repo's ITs). Documents and
 * new versions go through the real {@link DocumentVersionPromoter} with a
 * clean scan result - the scan-gate trigger (V39) is satisfied without a live
 * ClamAV, and a new version publishes the real DocumentVersionCreatedEvent.
 * Imported into a test with {@code @Import(WorkflowFixtures.class)}.
 */
public class WorkflowFixtures {

    public static final String[] APPROVER_PERMISSIONS = {
            "wa.approve-reject-workflow-task.approve", "wa.approve-reject-workflow-task.reject"};
    public static final String[] DESIGNER_PERMISSIONS = {
            "wa.configure-workflow-templates.create", "wa.configure-workflow-templates.update",
            "wa.configure-workflow-templates.delete", "wa.configure-workflow-templates.toggle-active"};
    public static final String SUBMIT_PERMISSION = "dlm.submit-documents-for-review";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ScanEngineRepository scanEngineRepository;
    private final ScanResultRepository scanResultRepository;
    private final DocumentVersionPromoter promoter;
    private final DocumentVersionRepository documentVersionRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    public WorkflowFixtures(TenantRepository tenantRepository, UserRepository userRepository,
                            RoleRepository roleRepository, UserRoleRepository userRoleRepository,
                            RolePermissionRepository rolePermissionRepository,
                            DepartmentRepository departmentRepository, DocumentTypeRepository documentTypeRepository,
                            StorageLocationRepository storageLocationRepository,
                            ScanEngineRepository scanEngineRepository, ScanResultRepository scanResultRepository,
                            DocumentVersionPromoter promoter, DocumentVersionRepository documentVersionRepository,
                            ObjectStorageServiceFactory objectStorageServiceFactory, JdbcTemplate jdbcTemplate,
                            PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.departmentRepository = departmentRepository;
        this.documentTypeRepository = documentTypeRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.scanEngineRepository = scanEngineRepository;
        this.scanResultRepository = scanResultRepository;
        this.promoter = promoter;
        this.documentVersionRepository = documentVersionRepository;
        this.objectStorageServiceFactory = objectStorageServiceFactory;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Writes real bytes for a version through the app's own storage (so the content endpoints can serve
     * them in an IT - the promoter records metadata but never stores bytes). Uploaded via resolve(), the
     * same path the readers use, so encryption at rest round-trips.
     */
    public void putVersionContent(UUID versionId, byte[] bytes) {
        DocumentVersion version = documentVersionRepository.findById(versionId).orElseThrow();
        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(version.getStorageLocationId()).orElseThrow();
        objectStorageServiceFactory.resolve(location)
                .upload(location, version.getStorageObjectKey(), new ByteArrayInputStream(bytes), bytes.length,
                        version.getMimeType());
    }

    /** A tenant with document + staging storage locations (paths are never written to). */
    public UUID tenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Workflow IT " + suffix)
                .domain("workflow-it-" + suffix + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.document, StoragePurpose.staging}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name("workflow-it-" + purpose + "-" + suffix)
                    .provider(StorageProvider.FILE_SYSTEM)
                    .purpose(purpose)
                    .basePath(System.getProperty("java.io.tmpdir") + "/workflow-it/" + suffix + "/" + purpose)
                    .status(StorageLocation.Status.active)
                    .build());
        }
        return tenant.getTenantId();
    }

    public User user(UUID tenantId, String firstName) {
        UUID keycloakId = UUID.randomUUID();
        return userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(firstName.toLowerCase() + "-" + keycloakId + "@workflow-it.invalid")
                .username(firstName.toLowerCase() + "-" + keycloakId)
                .firstName(firstName)
                .displayName(firstName)
                .status(User.UserStatus.active)
                .build());
    }

    /** A role granting the given permission codes, held by the given users. */
    public Role role(UUID tenantId, String name, String[] permissionCodes, User... holders) {
        Role role = roleRepository.save(Role.builder()
                .tenantId(tenantId)
                .roleName(name + " " + UUID.randomUUID().toString().substring(0, 8))
                .status(Role.RoleStatus.active)
                .build());
        for (String code : permissionCodes) {
            UUID permissionId = jdbcTemplate.queryForObject(
                    "select permission_id from permissions where permission_code = ? and tenant_id is null and is_deleted = false",
                    UUID.class, code);
            rolePermissionRepository.save(RolePermission.builder()
                    .tenantId(tenantId)
                    .roleId(role.getRoleId())
                    .permissionId(permissionId)
                    .build());
        }
        for (User holder : holders) {
            grant(holder, role);
        }
        return role;
    }

    public void grant(User user, Role role) {
        userRoleRepository.save(UserRole.builder()
                .tenantId(user.getTenantId())
                .userId(user.getUserId())
                .roleId(role.getRoleId())
                .scopeType(UserRole.ScopeType.tenant)
                .build());
    }

    public Department department(UUID tenantId, String name, User... members) {
        Department department = departmentRepository.save(Department.builder()
                .tenantId(tenantId)
                .name(name)
                .code(name.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_" + UUID.randomUUID().toString().substring(0, 6))
                .build());
        for (User member : members) {
            member.setDepartmentId(department.getDepartmentId());
            userRepository.save(member);
        }
        return department;
    }

    public UUID documentType(UUID tenantId, String name) {
        DocumentType type = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode(name.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                .typeName(name)
                .displayOrder(ThreadLocalRandom.current().nextInt(1_000, 1_000_000))
                .build());
        return type.getDocumentTypeId();
    }

    /** A document with one clean version, created through the real promoter. */
    public UUID document(UUID tenantId, UUID documentTypeId, User owner, String title) {
        return tx.execute(status -> {
            ScanResult scan = cleanScan(tenantId, owner.getUserId(), IngestionKind.new_document, "initial_upload")
                    .documentTypeId(documentTypeId)
                    .title(title)
                    .build();
            DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
            scan.setDocumentVersionId(plan.versionId());
            scanResultRepository.save(scan);
            promoter.persist(plan, scan);
            return plan.documentId();
        });
    }

    /** A new latest version of an existing document - publishes DocumentVersionCreatedEvent like a real upload. */
    public UUID newVersion(UUID tenantId, UUID documentId, User uploader) {
        return newVersion(tenantId, documentId, uploader, DocumentVersionService.REPLACEMENT_UPLOAD);
    }

    /**
     * {@link #newVersion} with the created_reason a given {@code DocumentVersionService} path sends
     * through the gate - {@code replacement_upload} for an upload over the document,
     * {@code rollback} for putting an older version back. Picks up at the promoter, the point the
     * gate reaches once the file has scanned clean, so no ClamAV is needed.
     */
    public UUID newVersion(UUID tenantId, UUID documentId, User uploader, String createdReason) {
        return tx.execute(status -> {
            ScanResult scan = cleanScan(tenantId, uploader.getUserId(), IngestionKind.new_version, createdReason)
                    .targetDocumentId(documentId)
                    .build();
            DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
            scan.setDocumentVersionId(plan.versionId());
            scanResultRepository.save(scan);
            promoter.persist(plan, scan);
            return plan.versionId();
        });
    }

    /** The real Jwt principal TenantContextFilter resolves; permissions come from the user's DB role grants. */
    public JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(b -> b.subject(user.getKeycloakId().toString()).claim("tenant_id", user.getTenantId().toString()))
                .authorities();
    }

    public JwtRequestPostProcessor tenantAdminJwtFor(User user) {
        return jwt().jwt(b -> b.subject(user.getKeycloakId().toString()).claim("tenant_id", user.getTenantId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    public long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    private ScanResult.ScanResultBuilder cleanScan(UUID tenantId, UUID uploadedBy, IngestionKind kind, String reason) {
        ScanEngine engine = scanEngineRepository.findPlatformDefault(ScanEngine.Status.active).orElseThrow();
        StorageLocation staging = storageLocationRepository
                .findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(tenantId, StoragePurpose.staging,
                        StorageLocation.Status.active)
                .orElseThrow();
        byte[] hash = new byte[32];
        ThreadLocalRandom.current().nextBytes(hash);
        return ScanResult.builder()
                .tenantId(tenantId)
                .scanEngineId(engine.getScanEngineId())
                .stagingLocationId(staging.getStorageLocationId())
                .originalFilename("contract.pdf")
                .fileHash(HexFormat.of().formatHex(hash))
                .sizeBytes(1024L)
                .mimeType("application/pdf")
                .status(ScanResultStatus.clean)
                .engineName("workflow-it")
                .scanMode(ScanMode.pre_store_stream)
                .completedAt(OffsetDateTime.now())
                .uploadedBy(uploadedBy)
                .ingestionKind(kind)
                .createdReason(reason)
                .createdBy(uploadedBy);
    }
}
