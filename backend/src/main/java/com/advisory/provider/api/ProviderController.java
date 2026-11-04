package com.advisory.provider.api;

import com.advisory.auth.domain.UserRole;
import com.advisory.auth.security.CurrentUser;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.provider.api.dto.CreateModelRequest;
import com.advisory.provider.api.dto.CreateProviderRequest;
import com.advisory.provider.api.dto.ImportModelsRequest;
import com.advisory.provider.api.dto.ModelResponse;
import com.advisory.provider.api.dto.PatchModelRequest;
import com.advisory.provider.api.dto.PatchProviderRequest;
import com.advisory.provider.api.dto.ProbeRequest;
import com.advisory.provider.api.dto.ProviderResponse;
import com.advisory.provider.api.dto.RemoteModelsResponse;
import com.advisory.provider.service.ProbeResult;
import com.advisory.provider.service.ProviderService;
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
 * 提供商与模型的接口。
 *
 * <p>读接口（{@code GET /api/providers}、带不带 {@code remote} 的
 * {@code GET /api/providers/{id}/models}）对所有已登录用户开放——Agent 配置页需要用这些列表渲染表单；
 * 唯独 {@code remote=true} 会真实调用外部服务并消耗已配置的密钥，因此仅限管理员（在方法体里显式判断而非
 * 用 SpEL，避免依赖参数名发现机制）。所有写操作与主动外联的接口（增删改、模型写入、{@code /test}、
 * {@code /import}）同样仅限管理员。任何响应都不携带 {@code api_key_cipher}。</p>
 */
@RestController
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 提供商

    /** 分页列出提供商，附带模型数与掩码后的密钥。 */
    @GetMapping("/api/providers")
    public ApiResponse<PageResponse<ProviderResponse>> listProviders(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    /** 新建提供商，密钥落库前加密。 */
    @PostMapping("/api/providers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest body) {
        return ApiResponse.ok(service.create(body));
    }

    /** 局部更新提供商；空串密钥表示清空，缺省表示不变。 */
    @PatchMapping("/api/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProviderResponse> updateProvider(@PathVariable Long id,
                                                        @RequestBody PatchProviderRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    /** 删除提供商，其下模型由数据库级联清理。 */
    @DeleteMapping("/api/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteProvider(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------ 某提供商下的模型

    /**
     * 默认返回本地登记的模型；{@code remote=true} 改为探测提供商，返回
     * {@code { ok, errorCode, models }} 与远端实时模型名。
     *
     * <p>远端探测会消耗已配置的 API Key 并访问外部主机，因此仅限管理员；本地列表则对所有已登录用户开放。</p>
     *
     * @param id     提供商主键
     * @param remote 是否探测远端
     * @return 本地模型列表或远端探测结果
     */
    @GetMapping("/api/providers/{id}/models")
    public ApiResponse<?> listModels(@PathVariable Long id,
                                     @RequestParam(name = "remote", defaultValue = "false") boolean remote,
                                     CurrentUser user) {
        if (remote && user.role() != UserRole.ADMIN) {
            throw new AppException(ErrorCode.FORBIDDEN, "remote probing requires the ADMIN role");
        }
        if (remote) {
            return ApiResponse.ok(RemoteModelsResponse.from(service.remoteModels(id)));
        }
        return ApiResponse.ok(service.listModels(id));
    }

    /** 登记一个本地模型，向量模型需声明维度。 */
    @PostMapping("/api/providers/{id}/models")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ModelResponse> createModel(@PathVariable Long id,
                                                  @Valid @RequestBody CreateModelRequest body) {
        return ApiResponse.ok(service.createModel(id, body));
    }

    /**
     * 先探测，再按 {@code (provider_id, model_name)} 幂等导入。
     *
     * <p>探测失败时：{@code MODEL_NOT_FOUND} 返回 404，其余返回 400，消息中带上探测错误码；
     * 请求中只要有非法行，就一行都不插入。</p>
     *
     * @param id      提供商主键
     * @param request 待导入的模型规格
     * @return 导入后的本地模型列表
     */
    @PostMapping("/api/providers/{id}/models/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ModelResponse>> importModels(@PathVariable Long id,
                                                         @RequestBody ImportModelsRequest body) {
        return ApiResponse.ok(service.importModels(id, body));
    }

    // ------------------------------------------------------------------ 连通性

    /** 对提供商做实时连通性探测。 */
    @PostMapping("/api/providers/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProbeResult> testProvider(@PathVariable Long id,
                                                 @RequestBody(required = false) ProbeRequest body) {
        String modelName = body == null ? null : body.modelName();
        return ApiResponse.ok(service.test(id, modelName));
    }

    // ------------------------------------------------------------------ 单个模型

    @PatchMapping("/api/models/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ModelResponse> updateModel(@PathVariable Long id,
                                                  @RequestBody PatchModelRequest body) {
        return ApiResponse.ok(service.updateModel(id, body));
    }

    @DeleteMapping("/api/models/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteModel(@PathVariable Long id) {
        service.deleteModel(id);
        return ApiResponse.ok();
    }
}
