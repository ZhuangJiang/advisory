package com.advisory.agent.api.dto;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.domain.AgentVersion;

import java.time.Instant;

/** One row of the read-only version list; the snapshot is exactly what the version stored. */
public record AgentVersionResponse(int version, AgentSnapshot snapshot, Instant createdAt) {

    public static AgentVersionResponse from(AgentVersion version) {
        return new AgentVersionResponse(version.version(), version.snapshot(), version.createdAt());
    }
}
