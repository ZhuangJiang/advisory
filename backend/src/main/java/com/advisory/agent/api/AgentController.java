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
 * Agent 相关接口。
 *
 * <p>列表与详情对所有已登录用户开放——对话页需要让普通用户选择一个 Agent 来提问；新建、修改、删除与
 * 版本历史仅限管理员（spec §8）。版本列表之所以也限管理员，是因为它逐版暴露了完整的提示词与检索配置。</p>
 */
@RestController
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    /** 分页列出 Agent，任意已登录用户可见。 */
    @GetMapping("/api/agents")
    public ApiResponse<PageResponse<AgentResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    /** 新建 Agent 并写入版本 1。 */
    @PostMapping("/api/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentResponse> create(@Valid @RequestBody CreateAgentRequest body) {
        return ApiResponse.ok(service.create(body));
    }

    /** Agent 详情，含当前生效版本号。 */
    @GetMapping("/api/agents/{id}")
    public ApiResponse<AgentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 局部更新 Agent，成功后追加一个新版本。 */
    @PatchMapping("/api/agents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody UpdateAgentRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    /** 删除 Agent；仍有关联会话时返回 409，需先归档。 */
    @DeleteMapping("/api/agents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 只读的版本历史，仅管理员可见。 */
    @GetMapping("/api/agents/{id}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<AgentVersionResponse>> versions(@PathVariable Long id) {
        return ApiResponse.ok(service.listVersions(id));
    }
}
