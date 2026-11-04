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
 * Document endpoints.
 *
 * <p>Reads (get/list) are open to any authenticated user so a USER can follow ingestion progress;
 * presign, create, retry and delete are ADMIN-only because a USER may only read knowledge bases.
 * Create answers 201 for a new row and 200 when the same {@code objectKey} is replayed.</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping("/presign")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PresignResponse> presign(@Valid @RequestBody PresignRequest body) {
        return ApiResponse.ok(service.presign(body));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DocumentResponse>> create(@Valid @RequestBody CreateDocumentRequest body,
                                                                CurrentUser user) {
        DocumentService.Creation creation = service.create(body, user.id());
        HttpStatus status = creation.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(creation.document()));
    }

    @GetMapping
    public ApiResponse<PageResponse<DocumentResponse>> list(
            @RequestParam(name = "kbId", required = false) Long kbId,
            @RequestParam(name = "nodeId", required = false) Long nodeId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(kbId, nodeId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** Stored chunks of one document, in {@code seq} order — what the UI's chunk drawer shows. */
    @GetMapping("/{id}/chunks")
    public ApiResponse<PageResponse<ChunkResponse>> chunks(
            @PathVariable Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.listChunks(id, page, size));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<DocumentResponse> retry(@PathVariable Long id) {
        return ApiResponse.ok(service.retry(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
