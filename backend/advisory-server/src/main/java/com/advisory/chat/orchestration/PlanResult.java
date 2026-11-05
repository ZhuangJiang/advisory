package com.advisory.chat.orchestration;

import java.util.List;

/**
 * PLAN 的结构化输出：{@code {"queries":[...]}}，即 1–3 条覆盖问题不同角度的检索语句。
 *
 * <p>空值与空白项会被丢弃，列表由编排器截断到三条；若最终为空，则退回使用用户原始问题。</p>
 */
public record PlanResult(List<String> queries) {
}
