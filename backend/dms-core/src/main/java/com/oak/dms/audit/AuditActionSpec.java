package com.oak.dms.audit;

/**
 * Everything about an audit action that must come from one central place
 * rather than being decided ad hoc at each call site: which
 * permission_modules.module_code it belongs to, its severity (per the
 * severity column's "static action-to-severity mapping" rule), and
 * whether a reason is mandatory for it (per the reason field's "defined
 * per action type in the event catalogue, not decided ad hoc per
 * screen" rule). See {@link AuditActionCatalog}.
 */
public record AuditActionSpec(String module, AuditSeverity severity, boolean reasonRequired) {
}
