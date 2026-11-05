package com.advisory.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/conversations/{id}/messages} 的请求体：开启 SSE 流的那个问题。 */
public record SendMessageRequest(@NotBlank @Size(max = 8000) String content) {
}
