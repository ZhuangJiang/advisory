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
 * 管理员的会话审计接口（spec §12 的 {@code audit}）。
 *
 * <p>整个控制器仅限 {@code ADMIN}，普通用户会由方法级安全直接挡回 403，而不是拿到一份被过滤的列表。
 * 会话不存在时对管理员同样返回 404——这里不存在「看不见」的情况；面向用户的对话接口才需要用 404
 * 来掩盖他人会话的存在。</p>
 */
@RestController
@RequestMapping("/api/admin/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    /** 按用户、Agent 与时间范围分页审计会话，最新在前。 */
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

    /** 查看会话详情，并记录一行 adminAudit 日志作为访问审计。 */
    @GetMapping("/{id}")
    public ApiResponse<AuditConversationDetail> detail(@PathVariable Long id, CurrentUser actor) {
        return ApiResponse.ok(service.detail(id, actor));
    }
}
