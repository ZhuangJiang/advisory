package com.advisory.agent.api;

import com.advisory.agent.api.dto.AgentTestRunRequest;
import com.advisory.agent.api.dto.AgentTestRunResponse;
import com.advisory.agent.service.AgentTestRunService;
import com.advisory.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/agents/{id}/test}——试运行（spec §12）。
 *
 * <p>仅限管理员（spec §8：普通用户可以对话，但不能查看 Agent 配置，也不该用试运行消耗提供商调用额度）。
 * 因为既没有流式输出也不落库，响应就是常规信封加上完整的推理轨迹。</p>
 */
@RestController
public class AgentTestController {

    private final AgentTestRunService service;

    public AgentTestController(AgentTestRunService service) {
        this.service = service;
    }

    /** 试运行指定版本，结果只出现在响应里，不落库。 */
    @PostMapping("/api/agents/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AgentTestRunResponse> test(@PathVariable Long id,
                                                  @Valid @RequestBody AgentTestRunRequest body) {
        return ApiResponse.ok(service.run(id, body.question(), body.version()));
    }
}
