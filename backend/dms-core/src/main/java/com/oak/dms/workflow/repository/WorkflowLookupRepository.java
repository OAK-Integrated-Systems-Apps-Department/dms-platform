package com.oak.dms.workflow.repository;

import com.oak.dms.user.User;
import com.oak.dms.workflow.dto.LookupOptionResponse;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Minimal, tenant-scoped pick lists for the template stage builder - names
 * only, active rows only - so a template designer doesn't need the IAM/MDTM
 * permissions the full admin lists require (plan R8).
 */
public interface WorkflowLookupRepository extends Repository<User, UUID> {

    @Query("""
            select new com.oak.dms.workflow.dto.LookupOptionResponse(u.userId, u.displayName, u.email)
            from User u
            where u.tenantId = :tenantId and u.deleted = false and u.status = com.oak.dms.user.User.UserStatus.active
            order by u.displayName
            """)
    List<LookupOptionResponse> activeUsers(@Param("tenantId") UUID tenantId);

    @Query("""
            select new com.oak.dms.workflow.dto.LookupOptionResponse(r.roleId, r.roleName, cast(null as string))
            from com.oak.dms.security.rbac.entity.Role r
            where r.tenantId = :tenantId and r.deleted = false
              and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
            order by r.roleName
            """)
    List<LookupOptionResponse> activeRoles(@Param("tenantId") UUID tenantId);

    @Query("""
            select new com.oak.dms.workflow.dto.LookupOptionResponse(t.documentTypeId, t.typeName, cast(null as string))
            from com.oak.dms.metadata.entity.DocumentType t
            where t.tenantId = :tenantId and t.deleted = false
              and t.status = com.oak.dms.metadata.entity.DocumentType.DocumentTypeStatus.active
            order by t.displayOrder, t.typeName
            """)
    List<LookupOptionResponse> activeDocumentTypes(@Param("tenantId") UUID tenantId);
}
