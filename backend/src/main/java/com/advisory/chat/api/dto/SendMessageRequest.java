package com.advisory.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/conversations/{id}/messages} — the question that starts an SSE stream. */
public record SendMessageRequest(@NotBlank @Size(max = 8000) String content) {
}
