package com.advisory.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorAlwaysSerializesMessageAndOmitsNullData() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.error(40000, null));

        assertThat(json)
                .contains("\"code\":40000")
                .contains("\"message\":null")
                .doesNotContain("\"data\"");
    }

    @Test
    void errorSerializesProvidedMessage() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.error(40900, "conflict"));

        assertThat(json)
                .contains("\"message\":\"conflict\"")
                .doesNotContain("\"data\"");
    }

    @Test
    void okSerializesEnvelopeWithData() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.ok(java.util.Map.of("id", 1)));

        assertThat(json)
                .contains("\"code\":0")
                .contains("\"message\":\"ok\"")
                .contains("\"data\":{\"id\":1}");
    }
}
