package com.oak.dms.checkout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Force-releasing a lock and placing or lifting a legal hold each need a written reason (HIGH audit). */
public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
}
