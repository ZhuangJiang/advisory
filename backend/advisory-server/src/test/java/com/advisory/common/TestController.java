package com.advisory.common;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供测试使用的控制器，用于在 Web MVC 切片里驱动 {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/_test")
public class TestController {

    @GetMapping("/not-found")
    void notFound() {
        throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "x");
    }

    @PostMapping("/validate")
    void validate(@Valid @RequestBody CreateRequest body) {
        // 空实现：校验发生在进入方法体之前
    }

    @GetMapping("/page")
    void page(@RequestParam int page) {
        // 空实现：类型转换发生在进入方法体之前
    }

    @GetMapping("/required")
    void required(@RequestParam @NotBlank String name) {
        // 空实现：参数校验发生在进入方法体之前
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("boom");
    }

    public record CreateRequest(@NotBlank String name) {
    }
}
