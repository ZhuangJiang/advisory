package com.advisory.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/agents/{id}/test} 的请求体：针对某个 Agent 版本提一个试运行问题。
 *
 * @param question 要提的问题，与真实消息一样受 8000 字符上限约束
 * @param version  要运行哪个版本的快照；{@code null} 表示当前版本。传入更早的版本会从
 *                 {@code agent_versions} 中还原，因此可以复现「修改之前，已有会话会怎么回答」
 */
public record AgentTestRunRequest(@NotBlank String question, Integer version) {
}
