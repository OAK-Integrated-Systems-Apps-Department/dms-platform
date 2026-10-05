package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record StageInstanceView(
        UUID stageInstanceId,
        int stageOrder,
        String name,
        String status,
        String completionRule,
        String rejectionRule,
        int entryCount,
        int requiredCount,
        int approvedCount,
        int rejectedCount,
        OffsetDateTime activatedAt,
        OffsetDateTime dueAt,
        OffsetDateTime completedAt,
        List<TaskView> tasks
) {
}
