package com.oak.dms.department.repository;

import com.oak.dms.department.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByDepartmentIdAndTenantIdAndDeletedFalse(UUID departmentId, UUID tenantId);

    List<Department> findByDepartmentIdInAndTenantIdAndDeletedFalse(Collection<UUID> departmentIds, UUID tenantId);

    boolean existsByTenantIdAndCodeAndDeletedFalse(UUID tenantId, String code);

    boolean existsByParentDepartmentIdAndDeletedFalse(UUID parentDepartmentId);

    /** Optional filters: status, and a case-insensitive name/code search. */
    @Query("""
            select d from Department d
            where d.tenantId = :tenantId and d.deleted = false
              and (:status is null or d.status = :status)
              and (:search is null or lower(d.name) like :search or lower(d.code) like :search)
            """)
    Page<Department> search(@Param("tenantId") UUID tenantId, @Param("status") Department.DepartmentStatus status,
                            @Param("search") String search, Pageable pageable);

    @Query("select count(u) from User u where u.tenantId = :tenantId and u.departmentId = :departmentId and u.deleted = false")
    long countMembers(@Param("tenantId") UUID tenantId, @Param("departmentId") UUID departmentId);

    @Query("select u from User u where u.tenantId = :tenantId and u.departmentId = :departmentId and u.deleted = false order by u.displayName")
    List<com.oak.dms.user.User> findMembers(@Param("tenantId") UUID tenantId, @Param("departmentId") UUID departmentId);
}
