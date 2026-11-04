package com.advisory.agent.api;

import com.advisory.agent.api.dto.AgentTestRunRequest;
import com.advisory.agent.api.dto.AgentTestRunResponse;
import com.advisory.agent.service.AgentTestRunService;
import com.advisory.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/agents/{id}/test} — trial run (spec §12).
 *
 * <p>ADMIN only (spec §8: a USER may chat with an agent but may not inspect its configuration or
 * spend provider calls on trial runs). The response is the usual envelope and the whole trace, since
 * there is no stream and nothing is stored.</p>
 */
@RestController
public class AgentTestController {

    private final AgentTestRunService service;

    public AgentTestController(AgentTestRunService service) {
        this.service = service;
    }

    @PostMapping("/api/agents/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentTestRunResponse> test(@PathVariable Long id,
                                                  @Valid @RequestBody AgentTestRunRequest body) {
        return ApiResponse.ok(service.run(id, body.question(), body.version()));
    }
}
