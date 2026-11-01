package com.advisory.knowledge.api;

import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
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
 * Node-tree endpoints. Reads are open to any authenticated user; writes are ADMIN-only.
 *
 * <p>The {@code PATCH} body distinguishes "parentId present" (including {@code null} = move to
 * root) from "parentId absent" (no move) by inspecting the raw JSON, because a DTO cannot tell
 * "absent" from "null". That also means the two fields are validated by hand: {@code name} must be a
 * string and {@code parentId} must be {@code null} or an integral number, otherwise the request is
 * rejected with {@code VALIDATION_FAILED} instead of being coerced to {@code 0} and reported as a
 * 404.</p>
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
        if (!body.isObject()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Request body must be a JSON object");
        }

        String name = null;
        if (body.has("name")) {
            JsonNode nameNode = body.get("name");
            if (!nameNode.isTextual()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "name must be a string");
            }
            name = nameNode.textValue();
        }

        boolean move = body.has("parentId");
        Long parentId = null;
        if (move) {
            JsonNode parentNode = body.get("parentId");
            if (!parentNode.isNull()) {
                if (!parentNode.isIntegralNumber() || !parentNode.canConvertToLong()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED, "parentId must be a number or null");
                }
                parentId = parentNode.longValue();
            }
        }

        return ApiResponse.ok(NodeResponse.from(service.updateNode(id, name, parentId, move)));
    }

    @DeleteMapping("/api/nodes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteNode(id);
        return ApiResponse.ok();
    }
}
