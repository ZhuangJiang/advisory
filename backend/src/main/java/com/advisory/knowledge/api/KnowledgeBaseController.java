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
 * Knowledge base endpoints. Listing is open to any authenticated user; writes are ADMIN-only.
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest body,
                                                     CurrentUser user) {
        return ApiResponse.ok(service.create(body, user.id()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable Long id,
                                                     @RequestBody UpdateKnowledgeBaseRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
