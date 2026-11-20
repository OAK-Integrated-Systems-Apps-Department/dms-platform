package com.oak.dms.reporting;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** A refused report request - always carries a stable {@code code}; messages are user-safe (no SQL, stack or object keys). */
public class ReportException extends ApiException {

    private ReportException(HttpStatus status, String code, String message, Object details) {
        super(status, code, message, details);
    }

    /** 403 {@code REPORTS_DISABLED}: the platform switch or the organisation's own switch is off. */
    public static ReportException disabled() {
        return new ReportException(HttpStatus.FORBIDDEN, "REPORTS_DISABLED",
                "Reports are turned off for your organisation.", null);
    }

    /** 422 with one message per offending field, for the admin screens to show inline. */
    public static ReportException invalid(String code, String message, Map<String, String> fieldErrors) {
        return new ReportException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, fieldErrors);
    }

    public static ReportException unprocessable(String code, String message) {
        return new ReportException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, null);
    }

    /** 429: too many runs at once - interactive requests are refused, scheduled ones wait in the queue. */
    public static ReportException rateLimited(String message) {
        return new ReportException(HttpStatus.TOO_MANY_REQUESTS, "REPORT_RATE_LIMITED", message, null);
    }

    /** 410: the file existed but has expired. */
    public static ReportException gone(String code, String message) {
        return new ReportException(HttpStatus.GONE, code, message, null);
    }

    /** 403 with a stable code: the caller is signed in but may not do this. */
    public static ReportException forbidden(String code, String message) {
        return new ReportException(HttpStatus.FORBIDDEN, code, message, null);
    }

    public static ReportException notFound(String code, String message) {
        return new ReportException(HttpStatus.NOT_FOUND, code, message, null);
    }

    public static ReportException conflict(String code, String message) {
        return new ReportException(HttpStatus.CONFLICT, code, message, null);
    }

    /** 409 that also tells the client how much is in the way, e.g. {@code {schedules: 3}}. */
    public static ReportException conflict(String code, String message, Map<String, Object> details) {
        return new ReportException(HttpStatus.CONFLICT, code, message, details);
    }
}
