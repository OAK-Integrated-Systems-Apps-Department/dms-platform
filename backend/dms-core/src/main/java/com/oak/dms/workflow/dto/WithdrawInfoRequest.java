package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.Size;

public record WithdrawInfoRequest(@Size(max = 2000) String reason) {
}
