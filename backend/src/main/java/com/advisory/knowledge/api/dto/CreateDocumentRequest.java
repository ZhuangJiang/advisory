package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 上传流程的第三步：登记客户端刚直传到 MinIO 的那个对象。
 *
 * <p>以 {@code objectKey} 幂等：重复调用返回已存在的文档，不会产生重复行。</p>
 */
public record CreateDocumentRequest(
        @NotNull(message = "must not be null")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "must not be blank")
        @Size(max = 512, message = "must not exceed 512 characters")
        String filename,

        @NotBlank(message = "must not be blank")
        @Size(max = 1024, message = "must not exceed 1024 characters")
        String objectKey,

        @PositiveOrZero(message = "must be >= 0")
        Long size,

        @Size(max = 128, message = "must not exceed 128 characters")
        String checksum) {
}
