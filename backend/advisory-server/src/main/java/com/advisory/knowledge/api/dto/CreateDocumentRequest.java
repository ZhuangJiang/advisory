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
        @NotNull(message = "不能为空")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过 512 个字符")
        String filename,

        @NotBlank(message = "不能为空")
        @Size(max = 1024, message = "长度不能超过 1024 个字符")
        String objectKey,

        @PositiveOrZero(message = "不能小于 0")
        Long size,

        @Size(max = 128, message = "长度不能超过 128 个字符")
        String checksum) {
}
