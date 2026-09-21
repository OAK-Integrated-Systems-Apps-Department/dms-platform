package com.oak.dms.audit.enums;

/**
 * The factor that was actually verified and succeeded for an auth event -
 * never the factor merely submitted or attempted. A failed MFA challenge
 * must not be recorded as an MFA-authenticated event; the caller (only
 * AuthService, on its own success paths) is responsible for only ever
 * passing the method that actually completed verification.
 */
public enum AuditAuthMethod {
    PASSWORD, MFA, SSO
}
