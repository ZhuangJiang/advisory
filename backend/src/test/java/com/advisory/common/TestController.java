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
 * Test-only controller used to exercise {@code GlobalExceptionHandler} in a Web MVC slice.
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
        // no-op: validation happens before the method body is entered
    }

    @GetMapping("/page")
    void page(@RequestParam int page) {
        // no-op: type conversion happens before the method body is entered
    }

    @GetMapping("/required")
    void required(@RequestParam @NotBlank String name) {
        // no-op: parameter validation happens before the method body is entered
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("boom");
    }

    public record CreateRequest(@NotBlank String name) {
    }
}
