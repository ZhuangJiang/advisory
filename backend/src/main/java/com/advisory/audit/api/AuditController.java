package com.advisory.audit.api;

import com.advisory.audit.dto.AuditConversationDetail;
import com.advisory.audit.dto.AuditConversationRow;
import com.advisory.audit.service.AuditQueryService;
import com.advisory.auth.security.CurrentUser;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator audit endpoints (spec §12 {@code audit}).
 *
 * <p>The whole controller is {@code ADMIN}-only, so a USER gets the envelope 403 from method
 * security rather than a filtered list. A missing conversation is 404 for an administrator too —
 * there is no "invisible" case here, unlike the user-facing chat endpoints where 404 hides other
 * people's threads.</p>
 */
@RestController
@RequestMapping("/api/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AuditConversationRow>> list(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "agentId", required = false) Long agentId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(userId, agentId, from, to, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AuditConversationDetail> detail(@PathVariable Long id, CurrentUser actor) {
        return ApiResponse.ok(service.detail(id, actor));
    }
}
