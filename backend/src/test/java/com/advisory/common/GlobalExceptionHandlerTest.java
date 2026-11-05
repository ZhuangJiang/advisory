package com.advisory.common;

import com.advisory.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void mapsAppExceptionToCodeAndStatus() throws Exception {
        mockMvc.perform(get("/api/_test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("x"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsValidationFailureTo40000WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/_test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message", containsString("name")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsUnexpectedExceptionTo50000WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/_test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message", not(containsString("boom"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsUnknownPathTo40400() throws Exception {
        mockMvc.perform(get("/api/_test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsMalformedJsonTo40000WithoutLeakingBody() throws Exception {
        mockMvc.perform(post("/api/_test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andExpect(content().string(not(containsString("not-json"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsTypeMismatchTo40000WithoutLeakingValue() throws Exception {
        mockMvc.perform(get("/api/_test/page").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求参数不合法"))
                .andExpect(content().string(not(containsString("abc"))))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsRequestParamValidationTo40000() throws Exception {
        mockMvc.perform(get("/api/_test/required").param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mapsUnsupportedMethodTo405With40000() throws Exception {
        mockMvc.perform(delete("/api/_test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message", is("请求方法不被支持")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
