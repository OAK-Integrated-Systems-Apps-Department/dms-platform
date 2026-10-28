package com.oak.dms.notification.dto;

/** The caller's unread, un-archived notification count (3.2, N12) - drives the bell badge. */
public record UnreadCountResponse(long count) {
}
