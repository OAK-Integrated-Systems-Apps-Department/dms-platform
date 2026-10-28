package com.oak.dms.folder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One user or role let into a restricted folder, at a level (folder_access, V53). */
@Entity
@Table(name = "folder_access")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FolderAccessEntry {

    public enum PrincipalType { user, role }

    @Id
    @UuidGenerator
    @Column(name = "folder_access_id", updatable = false, nullable = false)
    private UUID folderAccessId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "folder_id", nullable = false, updatable = false)
    private UUID folderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false)
    private PrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false)
    private FolderAccessLevel accessLevel;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
