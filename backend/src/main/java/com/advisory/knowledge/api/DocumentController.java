package com.advisory.knowledge.api;

import com.advisory.auth.security.CurrentUser;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import com.advisory.knowledge.api.dto.ChunkResponse;
import com.advisory.knowledge.api.dto.CreateDocumentRequest;
import com.advisory.knowledge.api.dto.DocumentResponse;
import com.advisory.knowledge.api.dto.PresignRequest;
import com.advisory.knowledge.api.dto.PresignResponse;
import com.advisory.knowledge.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档相关接口。
 *
 * <p>读接口（详情、列表）对所有已登录用户开放，普通用户因此能看到入库进度；预签名、登记、重试与删除
 * 仅限管理员——普通用户对知识库只读。登记接口新建时返回 201，同一个 {@code objectKey} 重放时返回 200。</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    /** 生成预签名 PUT 地址，客户端据此直传 MinIO。 */
    @PostMapping("/presign")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PresignResponse> presign(@Valid @RequestBody PresignRequest body) {
        return ApiResponse.ok(service.presign(body));
    }

    /** 登记已上传的对象；同一 objectKey 重放返回已有文档。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DocumentResponse>> create(@Valid @RequestBody CreateDocumentRequest body,
                                                                CurrentUser user) {
        DocumentService.Creation creation = service.create(body, user.id());
        HttpStatus status = creation.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(creation.document()));
    }

    /** 分页列出知识库下的文档及其入库状态。 */
    @GetMapping
    public ApiResponse<PageResponse<DocumentResponse>> list(
            @RequestParam(name = "kbId", required = false) Long kbId,
            @RequestParam(name = "nodeId", required = false) Long nodeId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(kbId, nodeId, page, size));
    }

    /** 文档详情；不存在或已删除返回 404。 */
    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 某个文档已落库的片段，按 {@code seq} 排序——即界面上的片段抽屉所展示的内容。 */
    @GetMapping("/{id}/chunks")
    public ApiResponse<PageResponse<ChunkResponse>> chunks(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.listChunks(id, page, size));
    }

    /** 把 FAILED 文档重新入队。 */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<DocumentResponse> retry(@PathVariable Long id) {
        return ApiResponse.ok(service.retry(id));
    }

    /** 软删除文档并异步清理对象与片段。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
