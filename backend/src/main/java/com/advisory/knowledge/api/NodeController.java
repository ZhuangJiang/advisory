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
 * 节点树接口。读操作对所有已登录用户开放，写操作仅限管理员。
 *
 * <p>{@code PATCH} 的请求体通过直接读原始 JSON 来区分「显式传了 parentId」（其中 {@code null} 表示移到根）
 * 与「没传 parentId」（不移动）——DTO 无法表达「字段缺省」与「字段为 null」的差别。这也意味着两个字段要手工校验：
 * {@code name} 必须是字符串，{@code parentId} 必须是 {@code null} 或整数，否则以 {@code VALIDATION_FAILED}
 * 拒绝，而不是被强转成 {@code 0} 之后报一个莫名其妙的 404。</p>
 */
@RestController
public class NodeController {

    private final NodeService service;

    public NodeController(NodeService service) {
        this.service = service;
    }

    /** 列出知识库的节点树，按路径排序保证父节点在前。 */
    @GetMapping("/api/knowledge-bases/{kbId}/nodes")
    public ApiResponse<List<NodeResponse>> list(@PathVariable Long kbId) {
        List<NodeResponse> nodes = service.listNodes(kbId).stream().map(NodeResponse::from).toList();
        return ApiResponse.ok(nodes);
    }

    /** 新建节点，同一父节点下不可重名。 */
    @PostMapping("/api/knowledge-bases/{kbId}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NodeResponse> create(@PathVariable Long kbId,
                                            @Valid @RequestBody CreateNodeRequest body) {
        return ApiResponse.ok(NodeResponse.from(service.createNode(kbId, body.parentId(), body.name())));
    }

    /** 重命名与移动二合一，通过原始 JSON 区分「未传」与「传 null」。 */
    @PatchMapping("/api/nodes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<NodeResponse> patch(@PathVariable Long id, @RequestBody JsonNode body) {
        if (!body.isObject()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "请求体必须是 JSON 对象");
        }

        String name = null;
        if (body.has("name")) {
            JsonNode nameNode = body.get("name");
            if (!nameNode.isTextual()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "name 必须是字符串");
            }
            name = nameNode.textValue();
        }

        boolean move = body.has("parentId");
        Long parentId = null;
        if (move) {
            JsonNode parentNode = body.get("parentId");
            if (!parentNode.isNull()) {
                if (!parentNode.isIntegralNumber() || !parentNode.canConvertToLong()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED, "parentId 必须是数字或 null");
                }
                parentId = parentNode.longValue();
            }
        }

        return ApiResponse.ok(NodeResponse.from(service.updateNode(id, name, parentId, move)));
    }

    /** 删除节点；有子节点或仍有文档时拒绝。 */
    @DeleteMapping("/api/nodes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteNode(id);
        return ApiResponse.ok();
    }
}
