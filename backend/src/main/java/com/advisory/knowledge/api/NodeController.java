package com.advisory.knowledge.api;

import com.advisory.common.api.ApiResponse;
import com.advisory.knowledge.api.dto.CreateNodeRequest;
import com.advisory.knowledge.api.dto.NodeResponse;
import com.advisory.knowledge.service.NodeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Node-tree endpoints. Reads are open to any authenticated user; writes are ADMIN-only. The
 * {@code PATCH} body distinguishes "parentId present" (including {@code null} = move to root) from
 * "parentId absent" (no move) by inspecting the raw JSON.
 */
@RestController
public class NodeController {

    private final NodeService service;

    public NodeController(NodeService service) {
        this.service = service;
    }

    @GetMapping("/api/knowledge-bases/{kbId}/nodes")
    public ApiResponse<List<NodeResponse>> list(@PathVariable Long kbId) {
        List<NodeResponse> nodes = service.listNodes(kbId).stream().map(NodeResponse::from).toList();
        return ApiResponse.ok(nodes);
    }

    @PostMapping("/api/knowledge-bases/{kbId}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NodeResponse> create(@PathVariable Long kbId,
                                            @Valid @RequestBody CreateNodeRequest body) {
        return ApiResponse.ok(NodeResponse.from(service.createNode(kbId, body.parentId(), body.name())));
    }

    @PatchMapping("/api/nodes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NodeResponse> patch(@PathVariable Long id, @RequestBody JsonNode body) {
        String name = body.hasNonNull("name") ? body.get("name").asText() : null;
        boolean move = body.has("parentId");
        Long parentId = (move && !body.get("parentId").isNull()) ? body.get("parentId").asLong() : null;
        return ApiResponse.ok(NodeResponse.from(service.updateNode(id, name, parentId, move)));
    }

    @DeleteMapping("/api/nodes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteNode(id);
        return ApiResponse.ok();
    }
}
