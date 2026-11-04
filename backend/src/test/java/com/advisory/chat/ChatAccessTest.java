package com.advisory.chat;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.security.CurrentUser;
import com.advisory.chat.service.ChatService;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.support.TestChatModelGatewayConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12: conversation isolation and the reconnect path.
 *
 * <p>The third case is the important one: it starts a real run through {@link ChatService} but never
 * hands the emitter to a servlet container, so every {@code send} is buffered and never delivered —
 * a client that vanished right after the first frame. The answer and all eight steps must still be
 * in the database and reachable through {@code GET /api/messages/{id}}.</p>
 */
class ChatAccessTest extends ChatFixture {

    @MockitoBean
    private RetrievalService retrievalService;

    @Autowired
    private ChatService chatService;

    private String otherToken;

    @BeforeEach
    void createSecondUser() throws Exception {
        User other = createUser(UserRole.USER);
        otherToken = login(other);
    }

    @Test
    void otherUsersConversationReturns404() throws Exception {
        long conversationId = createConversation(userToken);
        long messageId = insertAssistantMessage(conversationId);

        // the owner sees both
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/messages/" + messageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());

        // another USER gets 404 — not 403, so ids cannot be probed
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(get("/api/messages/" + messageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // and the conversation list is filtered, so it does not even show up
        String listed = mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, (int) JsonPath.read(listed, "$.data.total"),
                "another user's threads must not be listed");
        assertEquals(1, (int) JsonPath.read(mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString(), "$.data.total"));
    }

    @Test
    void adminCanReadAnyConversation() throws Exception {
        long conversationId = createConversation(userToken);
        long messageId = insertAssistantMessage(conversationId);

        mockMvc.perform(get("/api/messages/" + messageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId));

        // the admin list runs unfiltered (userId null), which is the branch a normal user never hits
        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void adminCannotPostIntoSomeoneElsesConversation() throws Exception {
        long conversationId = createConversation(userToken);

        // reading is allowed (spec §8: ADMIN 全量只读审计), writing on the user's behalf is not
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"替用户提问\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        assertEquals(0L, (long) jdbc.queryForObject(
                        "SELECT count(*) FROM messages WHERE conversation_id = ?", Long.class,
                        conversationId),
                "a rejected request must not leave a USER row behind");
    }

    @Test
    void adminCanAskInTheirOwnConversation() throws Exception {
        long adminConversation = createConversation(adminToken);

        mockMvc.perform(post("/api/conversations/" + adminConversation + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"报销时限是多久？\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void completedAnswerAndStepsSurviveDisconnect() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(chunk("第 7 条：报销需在 30 天内提交。")));
        long conversationId = createConversation(userToken);

        // The emitter is never returned to a container: nothing is ever flushed to a client.
        SseEmitter emitter = chatService.start(conversationId,
                new CurrentUser(userId, UserRole.USER), "报销时限是多久？");
        long messageId = awaitStatus(conversationId, "COMPLETED");

        String detail = mockMvc.perform(get("/api/messages/" + messageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps.length()").value(4))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        assertEquals(TestChatModelGatewayConfig.STUB_ANSWER, JsonPath.read(detail, "$.data.content"),
                "the answer must be persisted even though no frame reached the client");
        assertEquals("RETRIEVE", JsonPath.read(detail, "$.data.steps[1].phase"),
                "the persisted trace is complete and ordered");
        assertTrue(((String) JsonPath.read(detail, "$.data.steps[1].summary")).contains("召回"),
                "step summaries are stored alongside the payload");
        assertEquals("ANSWER", JsonPath.read(detail, "$.data.steps[3].phase"));
        emitter.complete();
    }

    // ------------------------------------------------------------------ helpers

    private long insertAssistantMessage(long conversationId) {
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, 'ASSISTANT', '历史回答', 'COMPLETED') RETURNING id
                """, Long.class, conversationId);
    }

    private long awaitStatus(long conversationId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM messages
                    WHERE conversation_id = ? AND role = 'ASSISTANT'
                    ORDER BY id DESC LIMIT 1
                    """, Long.class, conversationId);
            if (!ids.isEmpty() && expected.equals(messageStatus(ids.get(0)))) {
                return ids.get(0);
            }
            Thread.sleep(100);
        }
        fail("assistant message never reached " + expected);
        return -1;
    }
}
