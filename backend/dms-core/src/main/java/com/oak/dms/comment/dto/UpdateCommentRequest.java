package com.oak.dms.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommentRequest(@NotBlank @Size(max = 4000) String body) {
}
