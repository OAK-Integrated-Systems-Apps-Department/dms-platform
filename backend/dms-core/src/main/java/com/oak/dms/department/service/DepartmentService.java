package com.oak.dms.department.service;

import com.oak.dms.department.dto.AssignUserDepartmentRequest;
import com.oak.dms.department.dto.CreateDepartmentRequest;
import com.oak.dms.department.dto.DepartmentMemberResponse;
import com.oak.dms.department.dto.DepartmentResponse;
import com.oak.dms.department.dto.UpdateDepartmentRequest;
import com.oak.dms.department.dto.UserDepartmentResponse;
import com.oak.dms.department.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Departments of the caller's tenant (V43). Every lookup is tenant-scoped;
 * another tenant's department 404s.
 */
public interface DepartmentService {

    Page<DepartmentResponse> list(Department.DepartmentStatus status, String search, Pageable pageable);

    DepartmentResponse get(UUID departmentId);

    DepartmentResponse create(CreateDepartmentRequest request);

    DepartmentResponse update(UUID departmentId, UpdateDepartmentRequest request);

    void delete(UUID departmentId);

    List<DepartmentMemberResponse> members(UUID departmentId);

    UserDepartmentResponse getUserDepartment(UUID userId);

    UserDepartmentResponse assignUserDepartment(UUID userId, AssignUserDepartmentRequest request);
}
