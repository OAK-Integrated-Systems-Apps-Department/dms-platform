package com.oak.dms.ownership.service;

import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Validates who documents are transferred to (A3). Other tenant, missing, deactivated and inactive all look the same: 422. */
@Component
@RequiredArgsConstructor
public class OwnershipTargets {

    private static final String MESSAGE = "Choose an active user of your organisation as the new owner.";

    /** The new owner, the department they hold it under, and the state the documents end up in. */
    public record Target(UUID ownerUserId, String ownerName, UUID ownerDepartmentId, String state) {
    }

    private final UserRepository users;
    private final DepartmentRepository departments;

    public Target resolve(UUID tenantId, UUID newOwnerUserId, UUID newOwnerDepartmentId, String state) {
        if (newOwnerUserId == null) {
            throw OwnershipException.invalidTarget(MESSAGE);
        }
        User user = users.findByUserIdAndTenantIdAndDeletedFalse(newOwnerUserId, tenantId)
                .filter(u -> u.getStatus() == User.UserStatus.active)
                .orElseThrow(() -> OwnershipException.invalidTarget(MESSAGE));
        UUID department = user.getDepartmentId();
        if (newOwnerDepartmentId != null) {
            Department chosen = departments.findByDepartmentIdAndTenantIdAndDeletedFalse(newOwnerDepartmentId, tenantId)
                    .filter(d -> d.getStatus() == Department.DepartmentStatus.active)
                    .orElseThrow(() -> OwnershipException.invalidTarget("Choose an active department of your organisation."));
            department = chosen.getDepartmentId();
        }
        String name = user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getEmail();
        return new Target(user.getUserId(), name, department, state);
    }
}
