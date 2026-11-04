package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 上传流程的第一步：申请预签名 PUT 地址。
 *
 * <p>{@code nodeId} 可空（文档可以直接挂在知识库根上）；{@code size} 同样可空，但一旦提供就会按 50 MB
 * 上限校验，避免客户端把明显超限的文件传上来才发现。</p>
 */
public record PresignRequest(
        @NotNull(message = "must not be null")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "must not be blank")
        @Size(max = 512, message = "must not exceed 512 characters")
        String filename,

        @PositiveOrZero(message = "must be >= 0")
        Long size) {
}
