package com.oak.dms.importexport.enums;

/** One template row's outcome (migration_items.status, see ck_migration_items_status in V42). */
public enum MigrationItemStatus {
    pending,
    success,
    failed,
    cancelled
}
