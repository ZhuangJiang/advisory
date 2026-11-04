package com.advisory.agent.api;

import com.advisory.agent.api.dto.AgentResponse;
import com.advisory.agent.api.dto.AgentVersionResponse;
import com.advisory.agent.api.dto.CreateAgentRequest;
import com.advisory.agent.api.dto.UpdateAgentRequest;
import com.advisory.agent.service.AgentService;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent endpoints.
 *
 * <p>Listing and detail are open to every authenticated user (the chat page lets a USER pick an
 * agent to talk to); creation, modification, deletion and the version history are ADMIN-only
 * (spec §8). The version list is ADMIN-only because it exposes the full prompt and retrieval
 * configuration of every past version.</p>
 */
@RestController
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping("/api/agents")
    public ApiResponse<PageResponse<AgentResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @PostMapping("/api/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentResponse> create(@Valid @RequestBody CreateAgentRequest body) {
        return ApiResponse.ok(service.create(body));
    }

    @GetMapping("/api/agents/{id}")
    public ApiResponse<AgentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PatchMapping("/api/agents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody UpdateAgentRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/api/agents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/agents/{id}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<AgentVersionResponse>> versions(@PathVariable Long id) {
        return ApiResponse.ok(service.listVersions(id));
    }
}
