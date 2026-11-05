package com.advisory.knowledge.api;

import com.advisory.auth.security.CurrentUser;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import com.advisory.knowledge.api.dto.CreateKnowledgeBaseRequest;
import com.advisory.knowledge.api.dto.KnowledgeBaseResponse;
import com.advisory.knowledge.api.dto.UpdateKnowledgeBaseRequest;
import com.advisory.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口。列表对所有已登录用户开放，写操作仅限管理员。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    /** 分页列出知识库，附带实时文档数。 */
    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    /** 新建知识库，可先不指定向量模型。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest body,
                                                     CurrentUser user) {
        return ApiResponse.ok(service.create(body, user.id()));
    }

    /** 单个知识库及其实时文档数；id 不存在时返回 404。 */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 局部更新知识库；重建期间禁止改动向量模型与切片配置。 */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable Long id,
                                                     @RequestBody UpdateKnowledgeBaseRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    /**
     * 仅限管理员的全量重建。重建在后台执行，接口立即返回 202；已有重建在跑时返回 409
     * {@code KB_REINDEXING}。
     *
     * @param id 知识库主键
     */
    @PostMapping("/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> reindex(@PathVariable Long id) {
        service.reindex(id);
        return ApiResponse.ok();
    }

    /** 删除知识库；被 Agent 引用或正在重建时拒绝。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
