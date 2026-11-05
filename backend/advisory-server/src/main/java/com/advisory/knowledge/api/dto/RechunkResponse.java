package com.advisory.knowledge.api.dto;

import java.util.List;

/**
 * 批量重新裁切的结果。
 *
 * <p>批量操作不整单失败：单篇文档不满足条件（正在入库、已删除、知识库正在重建）只会被跳过，
 * 其余照常提交。前端据此提示「已提交 N 篇，跳过 M 篇」，而不是让用户一个个试。</p>
 *
 * @param requested 去重后的请求篇数
 * @param accepted  已排进裁切队列的篇数
 * @param skipped   被跳过的文档及原因
 */
public record RechunkResponse(int requested, int accepted, List<Skipped> skipped) {

    /**
     * 一篇被跳过的文档。
     *
     * @param id     文档主键
     * @param reason 展示给用户的跳过原因
     */
    public record Skipped(Long id, String reason) {
    }
}
