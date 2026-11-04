package com.advisory.auth;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 被置上 {@code must_change_password} 标记的用户（引导管理员、管理员新建的用户、被管理员重置过
 * 密码的用户）只能访问 {@code me} / {@code change-password} / {@code logout}；
 * 其它任何需要登录的请求都必须以 403 / 40301 失败。
 */
class MustChangePasswordTest extends IntegrationTestBase {

    private static final String INITIAL_PASSWORD = "initial-pass-1";
    private static final String NEW_PASSWORD = "brand-new-pass-2";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User user;

    @BeforeEach
    void createUserThatMustChangePassword() {
        user = new User("mustchange-" + UUID.randomUUID(), passwordEncoder.encode(INITIAL_PASSWORD), UserRole.USER);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user = userRepository.save(user);
    }

    @Test
    void blocksBusinessEndpointsUntilPasswordIsChanged() throws Exception {
        LoginResult login = login(INITIAL_PASSWORD);
        String bearer = "Bearer " + login.accessToken();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        mockMvc.perform(get("/api/knowledge-bases").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("Password change required"))
                .andExpect(jsonPath("$.data").doesNotExist());

        LoginResult changed = changePassword(bearer, INITIAL_PASSWORD, NEW_PASSWORD);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + changed.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 改密拦截已解除，于是这个（现在是真实的）知识库接口返回 200，
        // 而不是 403/40301。
        mockMvc.perform(get("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + changed.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 改密之前签发的 refresh token 已被吊销。
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + login.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 刚签发的那枚 refresh token 依然可用。
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + changed.refreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        LoginResult relogin = login(NEW_PASSWORD);
        mockMvc.perform(get("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + relogin.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsWrongOldPassword() throws Exception {
        String bearer = "Bearer " + login(INITIAL_PASSWORD).accessToken();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("wrong-old-pass", NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 仍处在拦截中：这次失败的尝试不得把标记清掉。
        mockMvc.perform(get("/api/knowledge-bases").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void rejectsNewPasswordEqualToOld() throws Exception {
        String bearer = "Bearer " + login(INITIAL_PASSWORD).accessToken();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(INITIAL_PASSWORD, INITIAL_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsBlankNewPassword() throws Exception {
        String bearer = "Bearer " + login(INITIAL_PASSWORD).accessToken();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(INITIAL_PASSWORD, "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private LoginResult login(String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new LoginResult(JsonPath.read(response, "$.data.accessToken"),
                JsonPath.read(response, "$.data.refreshToken"));
    }

    private LoginResult changePassword(String bearer, String oldPassword, String newPassword) throws Exception {
        String response = mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(oldPassword, newPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new LoginResult(JsonPath.read(response, "$.data.accessToken"),
                JsonPath.read(response, "$.data.refreshToken"));
    }

    private static String passwordBody(String oldPassword, String newPassword) {
        return "{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private record LoginResult(String accessToken, String refreshToken) {
    }
}
