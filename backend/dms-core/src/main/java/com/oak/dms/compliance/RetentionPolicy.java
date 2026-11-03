package com.oak.dms.compliance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One retention rule (V130): how long documents in its scope stay Active, and how long they then stay Archived before an
 * administrator is asked what to do. Everything here is data an administrator edits - durations, their units, where the
 * clock starts, the regulatory label, whether the rule is on.
 */
@Entity
@Table(name = "retention_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetentionPolicy {

    public static final String SCOPE_ORGANISATION = "organisation";
    public static final String SCOPE_DOCUMENT_TYPE = "document_type";
    public static final String SCOPE_DEPARTMENT = "department";
    public static final String SCOPE_FOLDER = "folder";

    public static final String CLOCK_CREATED = "created";
    public static final String CLOCK_LAST_MODIFIED = "last_modified";

    @Id
    @Column(name = "policy_id", updatable = false, nullable = false)
    private UUID policyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** organisation (the default for everything), document_type, department or folder. */
    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    /** The type, department or folder this rule covers; null for the organisation-wide rule. */
    @Column(name = "scope_id")
    private UUID scopeId;

    /** For a folder rule: also cover the folders inside it. */
    @Column(name = "include_subfolders", nullable = false)
    @Builder.Default
    private boolean includeSubfolders = true;

    @Column(name = "active_amount", nullable = false)
    private int activeAmount;

    @Column(name = "active_unit", nullable = false)
    private String activeUnit;

    @Column(name = "archive_amount", nullable = false)
    private int archiveAmount;

    @Column(name = "archive_unit", nullable = false)
    private String archiveUnit;

    /** created or last_modified: where the active period starts counting. */
    @Column(name = "clock_start", nullable = false)
    @Builder.Default
    private String clockStart = CLOCK_CREATED;

    /** A free label such as GDPR, HMRC or Companies Act 2006 - the rule itself is the configuration. */
    @Column(name = "framework")
    private String framework;

    /** Keep at most this many versions of a document (null = no limit). */
    @Column(name = "max_versions")
    private Integer maxVersions;

    /** Remove superseded versions older than this (both null = no age limit). */
    @Column(name = "version_age_amount")
    private Integer versionAgeAmount;

    @Column(name = "version_age_unit")
    private String versionAgeUnit;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public RetentionSpan activeSpan() {
        return new RetentionSpan(activeAmount, activeUnit);
    }

    /** The age limit for old versions, or null when the rule sets none. */
    public RetentionSpan versionAgeSpan() {
        return versionAgeAmount == null || versionAgeUnit == null ? null : new RetentionSpan(versionAgeAmount, versionAgeUnit);
    }

    /** True when the rule limits old versions at all. */
    public boolean limitsVersions() {
        return maxVersions != null || versionAgeSpan() != null;
    }

    public RetentionSpan archiveSpan() {
        return new RetentionSpan(archiveAmount, archiveUnit);
    }
}
