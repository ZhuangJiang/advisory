package com.advisory.agent.domain;

import java.time.Instant;

/**
 * {@code agent_versions} 的只读行：某 Agent 在某一版本上的快照。
 *
 * <p>这些行只会被插入（新建时写入版本 1，之后每次成功的 PATCH 再追加一版），除了随 Agent 删除而级联清理外
 * 不会被更新或删除——正是这种「只追加」让历史会话始终可复现。</p>
 */
public record AgentVersion(long id, long agentId, int version, AgentSnapshot snapshot, Instant createdAt) {
}
