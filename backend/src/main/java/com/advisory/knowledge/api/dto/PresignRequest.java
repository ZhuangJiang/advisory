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
        @NotNull(message = "不能为空")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过 512 个字符")
        String filename,

        @PositiveOrZero(message = "不能小于 0")
        Long size) {
}
