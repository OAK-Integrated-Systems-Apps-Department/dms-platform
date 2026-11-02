package com.oak.dms.department.service.impl;

import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.dto.AssignUserDepartmentRequest;
import com.oak.dms.department.dto.CreateDepartmentRequest;
import com.oak.dms.department.dto.DepartmentMemberResponse;
import com.oak.dms.department.dto.DepartmentResponse;
import com.oak.dms.department.dto.UpdateDepartmentRequest;
import com.oak.dms.department.dto.UserDepartmentResponse;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.exception.DepartmentStateException;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.department.service.DepartmentService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    /** Generous bound on hierarchy depth - only to stop a corrupted chain looping forever. */
    private static final int MAX_DEPTH = 100;

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public Page<DepartmentResponse> list(Department.DepartmentStatus status, String search, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        String pattern = search == null || search.isBlank() ? null : LikePatterns.contains(search.trim());
        Page<Department> page = departmentRepository.search(tenantId, status, pattern, pageable);
        Map<UUID, String> parentNames = new HashMap<>();
        Set<UUID> parentIds = new HashSet<>();
        page.forEach(d -> {
            if (d.getParentDepartmentId() != null) {
                parentIds.add(d.getParentDepartmentId());
            }
        });
        departmentRepository.findByDepartmentIdInAndTenantIdAndDeletedFalse(parentIds, tenantId)
                .forEach(p -> parentNames.put(p.getDepartmentId(), p.getName()));
        List<UUID> people = new java.util.ArrayList<>(page.map(Department::getHeadUserId).toList());
        people.addAll(page.map(Department::getHoldingOwnerUserId).toList());
        Map<UUID, String> heads = userNames(people, tenantId);
        return page.map(d -> toResponse(d, parentNames.get(d.getParentDepartmentId()), heads.get(d.getHeadUserId()),
                heads.get(d.getHoldingOwnerUserId()), departmentRepository.countMembers(tenantId, d.getDepartmentId())));
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentResponse get(UUID departmentId) {
        return toResponse(findOwned(departmentId));
    }

    @Override
    @Transactional
    public DepartmentResponse create(CreateDepartmentRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (departmentRepository.existsByTenantIdAndCodeAndDeletedFalse(tenantId, code)) {
            throw new DepartmentStateException("DEPARTMENT_CODE_EXISTS", "A department with code " + code + " already exists");
        }
        if (request.parentDepartmentId() != null) {
            findOwned(request.parentDepartmentId());
        }
        requireTenantUser(request.headUserId(), tenantId);

        Department saved = departmentRepository.save(Department.builder()
                .tenantId(tenantId)
                .name(request.name().trim())
                .code(code)
                .parentDepartmentId(request.parentDepartmentId())
                .headUserId(request.headUserId())
                .createdBy(userId)
                .build());

        auditService.event(AuditActionCatalog.DEPARTMENT_CREATED)
                .objectType(AuditObjectTypes.DEPARTMENT)
                .objectId(saved.getDepartmentId())
                .resourceName(saved.getName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(snapshot(saved))
                .log();
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DepartmentResponse update(UUID departmentId, UpdateDepartmentRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Department department = findOwned(departmentId);
        Map<String, Object> before = snapshot(department);

        if (request.parentDepartmentId() != null) {
            findOwned(request.parentDepartmentId());
            requireNoCycle(departmentId, request.parentDepartmentId(), tenantId);
        }
        requireTenantUser(request.headUserId(), tenantId);
        UUID holdingBefore = department.getHoldingOwnerUserId();
        UUID holdingAfter = holdingBefore;
        if (Boolean.TRUE.equals(request.clearHoldingOwner())) {
            holdingAfter = null;
        } else if (request.holdingOwnerUserId() != null) {
            User holder = findTenantUser(request.holdingOwnerUserId(), tenantId);
            if (holder.getStatus() != User.UserStatus.active) {
                throw new DepartmentStateException("HOLDING_OWNER_INACTIVE", "Choose an active user as the holding owner");
            }
            holdingAfter = holder.getUserId();
        }

        department.setName(request.name().trim());
        department.setHoldingOwnerUserId(holdingAfter);
        department.setParentDepartmentId(request.parentDepartmentId());
        department.setHeadUserId(request.headUserId());
        department.setStatus(request.status());
        department.setUpdatedBy(TenantContext.getUserId());
        department.setUpdatedAt(OffsetDateTime.now());
        Department saved = departmentRepository.save(department);

        auditService.event(AuditActionCatalog.DEPARTMENT_UPDATED)
                .objectType(AuditObjectTypes.DEPARTMENT)
                .objectId(saved.getDepartmentId())
                .resourceName(saved.getName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(snapshot(saved))
                .log();
        if (!java.util.Objects.equals(holdingBefore, holdingAfter)) {
            Map<String, Object> was = new HashMap<>();
            was.put("holdingOwnerUserId", holdingBefore);
            Map<String, Object> now = new HashMap<>();
            now.put("holdingOwnerUserId", holdingAfter);
            auditService.event(AuditActionCatalog.DEPARTMENT_HOLDING_OWNER_CHANGED)
                    .objectType(AuditObjectTypes.DEPARTMENT)
                    .objectId(saved.getDepartmentId())
                    .resourceName(saved.getName())
                    .outcome(AuditOutcome.SUCCESS)
                    .previousValue(was)
                    .newValue(now)
                    .log();
        }
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID departmentId) {
        UUID tenantId = TenantContext.getTenantId();
        Department department = findOwned(departmentId);
        if (departmentRepository.countMembers(tenantId, departmentId) > 0) {
            throw new DepartmentStateException("DEPARTMENT_HAS_MEMBERS",
                    "Move this department's users to another department first");
        }
        if (departmentRepository.existsByParentDepartmentIdAndDeletedFalse(departmentId)) {
            throw new DepartmentStateException("DEPARTMENT_HAS_CHILDREN", "Delete or move its child departments first");
        }
        if (participantRepository.isAssigneeInUse(tenantId, AssigneeType.department, departmentId)) {
            throw new DepartmentStateException("DEPARTMENT_IN_USE",
                    "A workflow template stage still routes approvals to this department");
        }
        OffsetDateTime now = OffsetDateTime.now();
        department.setDeleted(true);
        department.setDeletedAt(now);
        department.setUpdatedBy(TenantContext.getUserId());
        department.setUpdatedAt(now);
        departmentRepository.save(department);

        auditService.event(AuditActionCatalog.DEPARTMENT_DELETED)
                .objectType(AuditObjectTypes.DEPARTMENT)
                .objectId(departmentId)
                .resourceName(department.getName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(snapshot(department))
                .log();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentMemberResponse> members(UUID departmentId) {
        findOwned(departmentId);
        return departmentRepository.findMembers(TenantContext.getTenantId(), departmentId).stream()
                .map(u -> new DepartmentMemberResponse(u.getUserId(), u.getDisplayName(), u.getEmail(),
                        u.getStatus().name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserDepartmentResponse getUserDepartment(UUID userId) {
        User user = findTenantUser(userId, TenantContext.getTenantId());
        return userDepartment(user);
    }

    @Override
    @Transactional
    public UserDepartmentResponse assignUserDepartment(UUID userId, AssignUserDepartmentRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        User user = findTenantUser(userId, tenantId);
        UUID previous = user.getDepartmentId();
        if (request.departmentId() != null) {
            Department department = findOwned(request.departmentId());
            if (department.getStatus() != Department.DepartmentStatus.active) {
                throw new DepartmentStateException("DEPARTMENT_INACTIVE", "Users can only join an active department");
            }
        }
        if (Objects.equals(previous, request.departmentId())) {
            return userDepartment(user);
        }
        user.setDepartmentId(request.departmentId());
        user.setUpdatedBy(TenantContext.getUserId());
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        Map<String, Object> before = new HashMap<>();
        before.put("departmentId", previous);
        Map<String, Object> after = new HashMap<>();
        after.put("departmentId", request.departmentId());
        auditService.event(AuditActionCatalog.USER_DEPARTMENT_CHANGED)
                .objectType(AuditObjectTypes.USER)
                .objectId(userId)
                .resourceName(user.getDisplayName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(after)
                .log();
        return userDepartment(user);
    }

    // ---------------------------------------------------------------- helpers

    private Department findOwned(UUID departmentId) {
        return departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(departmentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", departmentId));
    }

    private User findTenantUser(UUID userId, UUID tenantId) {
        return userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private void requireTenantUser(UUID userId, UUID tenantId) {
        if (userId != null) {
            findTenantUser(userId, tenantId);
        }
    }

    /** Walks up from the proposed parent; reaching the department itself would close a loop. */
    private void requireNoCycle(UUID departmentId, UUID parentId, UUID tenantId) {
        UUID cursor = parentId;
        for (int depth = 0; cursor != null && depth < MAX_DEPTH; depth++) {
            if (cursor.equals(departmentId)) {
                throw new DepartmentStateException("DEPARTMENT_CYCLE", "A department cannot be its own ancestor");
            }
            cursor = departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(cursor, tenantId)
                    .map(Department::getParentDepartmentId)
                    .orElse(null);
        }
    }

    private UserDepartmentResponse userDepartment(User user) {
        String name = user.getDepartmentId() == null ? null
                : departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(user.getDepartmentId(), user.getTenantId())
                .map(Department::getName).orElse(null);
        return new UserDepartmentResponse(user.getUserId(), user.getDepartmentId(), name);
    }

    private DepartmentResponse toResponse(Department d) {
        UUID tenantId = d.getTenantId();
        String parentName = d.getParentDepartmentId() == null ? null
                : departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(d.getParentDepartmentId(), tenantId)
                .map(Department::getName).orElse(null);
        List<UUID> people = new java.util.ArrayList<>();
        people.add(d.getHeadUserId());
        people.add(d.getHoldingOwnerUserId());
        Map<UUID, String> names = userNames(people, tenantId);
        return toResponse(d, parentName, names.get(d.getHeadUserId()), names.get(d.getHoldingOwnerUserId()),
                departmentRepository.countMembers(tenantId, d.getDepartmentId()));
    }

    private static DepartmentResponse toResponse(Department d, String parentName, String headName,
                                                 String holdingOwnerName, long members) {
        return new DepartmentResponse(d.getDepartmentId(), d.getName(), d.getCode(), d.getParentDepartmentId(),
                parentName, d.getHeadUserId(), headName, d.getStatus().name(), members, d.getCreatedAt(),
                d.getUpdatedAt(), d.getHoldingOwnerUserId(), holdingOwnerName);
    }

    private Map<UUID, String> userNames(List<UUID> ids, UUID tenantId) {
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> wanted = new HashSet<>(ids);
        wanted.remove(null);
        userRepository.findAllById(wanted).forEach(u -> {
            if (tenantId.equals(u.getTenantId())) {
                names.put(u.getUserId(), u.getDisplayName());
            }
        });
        return names;
    }

    private static Map<String, Object> snapshot(Department d) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", d.getName());
        m.put("code", d.getCode());
        m.put("parentDepartmentId", d.getParentDepartmentId());
        m.put("headUserId", d.getHeadUserId());
        m.put("status", d.getStatus().name());
        return m;
    }
}
