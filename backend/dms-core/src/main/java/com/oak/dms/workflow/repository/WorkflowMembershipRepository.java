package com.oak.dms.workflow.repository;

import com.oak.dms.user.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Who is in an approver pool. Active, non-deleted users only. Role pools
 * count any grant of the role whatever its user_roles scope (Q3) - the same
 * rule RolePermissionRepository uses for permissions; department pools are
 * direct members only (users.department_id), never child departments.
 */
public interface WorkflowMembershipRepository extends Repository<User, UUID> {

    @Query("""
            select distinct u.userId from User u, com.oak.dms.security.rbac.entity.UserRole ur,
                 com.oak.dms.security.rbac.entity.Role r
            where ur.userId = u.userId and r.roleId = ur.roleId
              and ur.tenantId = :tenantId and ur.roleId = :roleId
              and r.deleted = false and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
              and u.tenantId = :tenantId and u.deleted = false and u.status = com.oak.dms.user.User.UserStatus.active
            """)
    List<UUID> findActiveRoleMemberIds(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

    @Query("""
            select u.userId from User u
            where u.tenantId = :tenantId and u.departmentId = :departmentId
              and u.deleted = false and u.status = com.oak.dms.user.User.UserStatus.active
            """)
    List<UUID> findActiveDepartmentMemberIds(@Param("tenantId") UUID tenantId, @Param("departmentId") UUID departmentId);

    /** The caller's active role ids - for the My Tasks pool branch and the live eligibility check. */
    @Query("""
            select distinct ur.roleId from com.oak.dms.security.rbac.entity.UserRole ur,
                 com.oak.dms.security.rbac.entity.Role r
            where r.roleId = ur.roleId and ur.userId = :userId and ur.tenantId = :tenantId
              and r.deleted = false and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
            """)
    List<UUID> findActiveRoleIds(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
