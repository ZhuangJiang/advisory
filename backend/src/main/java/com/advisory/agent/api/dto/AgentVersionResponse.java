package com.advisory.agent.api.dto;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.domain.AgentVersion;

import java.time.Instant;

/**
 * 只读版本列表中的一行。
 *
 * <p>{@code snapshot} 就是该版本写入时的原样内容，不做任何补全或迁移。</p>
 */
public record AgentVersionResponse(int version, AgentSnapshot snapshot, Instant createdAt) {

    public static AgentVersionResponse from(AgentVersion version) {
        return new AgentVersionResponse(version.version(), version.snapshot(), version.createdAt());
    }
}
