package com.oak.dms.ownership.dto;

import java.util.List;

public record OwnershipHistoryPage(List<OwnershipHistoryItem> items, int page, int size, long total) {
}
