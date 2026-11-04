package com.advisory.provider.api;

import com.advisory.common.api.ApiResponse;
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
 * Provider and model endpoints.
 *
 * <p>Reads ({@code GET /api/providers}, {@code GET /api/providers/{id}/models} with or without
 * {@code remote}) are open to any authenticated user — Task 16 renders the Agent form from the
 * provider/model lists. Everything that writes or reaches out to a provider (create/update/delete,
 * model writes, {@code /test}, {@code /import}) is ADMIN-only. Responses never carry
 * {@code api_key_cipher}.</p>
 */
@RestController
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ providers

    @GetMapping("/api/providers")
    public ApiResponse<List<ProviderResponse>> listProviders() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/api/providers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest body) {
        return ApiResponse.ok(service.create(body));
    }

    @PatchMapping("/api/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProviderResponse> updateProvider(@PathVariable Long id,
                                                        @RequestBody PatchProviderRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/api/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteProvider(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------ models of a provider

    /**
     * Local rows by default; {@code remote=true} probes the provider instead and returns
     * {@code { ok, errorCode, models }} with the live remote names.
     */
    @GetMapping("/api/providers/{id}/models")
    public ApiResponse<?> listModels(@PathVariable Long id,
                                     @RequestParam(name = "remote", defaultValue = "false") boolean remote) {
        if (remote) {
            return ApiResponse.ok(RemoteModelsResponse.from(service.remoteModels(id)));
        }
        return ApiResponse.ok(service.listModels(id));
    }

    @PostMapping("/api/providers/{id}/models")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ModelResponse> createModel(@PathVariable Long id,
                                                  @Valid @RequestBody CreateModelRequest body) {
        return ApiResponse.ok(service.createModel(id, body));
    }

    /**
     * Probes first, then imports the request idempotently by {@code (provider_id, model_name)}.
     * Probe failure is 404 for {@code MODEL_NOT_FOUND} and 400 otherwise, with the probe's error
     * code in the message; a request containing an invalid row inserts nothing.
     */
    @PostMapping("/api/providers/{id}/models/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ModelResponse>> importModels(@PathVariable Long id,
                                                         @RequestBody ImportModelsRequest body) {
        return ApiResponse.ok(service.importModels(id, body));
    }

    // ------------------------------------------------------------------ connectivity

    @PostMapping("/api/providers/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProbeResult> testProvider(@PathVariable Long id,
                                                 @RequestBody(required = false) ProbeRequest body) {
        String modelName = body == null ? null : body.modelName();
        return ApiResponse.ok(service.test(id, modelName));
    }

    // ------------------------------------------------------------------ single model

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
