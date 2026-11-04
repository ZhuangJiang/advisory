package com.advisory.audit;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.support.AdvisoryFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 13: the administrator audit endpoints.
 *
 * <p>Three things are under test, not just the happy path: USER is refused with 403 (spec §8 —
 * audit is ADMIN-only), the filters actually narrow the query (a filter that is silently dropped
 * would look identical to one that works when the database holds a single row), and opening a
 * detail writes the {@code adminAudit} log line the plan requires.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditAccessTest extends AdvisoryFixture {

    private long otherUserId;
    private String otherToken;
    private long secondAgentId;

    private long mine;
    private long theirs;

    @BeforeEach
    void createAuditRows() throws Exception {
        User other = createUser(UserRole.USER);
        otherUserId = other.getId();
        otherToken = login(other);
        secondAgentId = createAgent();

        mine = createConversation(userToken);
        jdbc.update("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, 'USER', '报销时限是多久？', 'COMPLETED')
                """, mine);
        long assistantId = jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, citations, status, latency_ms)
                VALUES (?, 'ASSISTANT', '30 天内 [1]。', CAST(? AS jsonb), 'COMPLETED', 1234)
                RETURNING id
                """, Long.class, mine, citationsJson());
        insertStep(assistantId, "PLAN", "生成 1 条检索查询", 12);
        insertStep(assistantId, "ANSWER", "给出带引用的回答", 900);

        theirs = createConversation(otherToken, secondAgentId);
        jdbc.update("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, 'USER', '他们的提问', 'COMPLETED')
                """, theirs);
    }

    @Test
    void adminSeesEveryConversationWithDenormalisedNames() throws Exception {
        String body = mockMvc.perform(get("/api/admin/conversations")
                        .param("agentId", String.valueOf(agentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn().getResponse().getContentAsString();

        assertEquals(mine, ((Number) JsonPath.read(body, "$.data.content[0].id")).longValue());
        assertEquals(username(userId), JsonPath.read(body, "$.data.content[0].userName"),
                "the audit list is readable without a second lookup per row");
        assertEquals(2, (int) JsonPath.read(body, "$.data.content[0].messageCount"));
        assertEquals(1, (int) JsonPath.read(body, "$.data.content[0].agentVersion"));
        assertEquals(agentId, ((Number) JsonPath.read(body, "$.data.content[0].agentId")).longValue());
        assertTrue(JsonPath.read(body, "$.data.content[0].lastMessageAt") != null,
                "lastMessageAt comes from the newest message, not from the conversation row");
        assertTrue(JsonPath.read(body, "$.data.content[0].agentName") != null);
    }

    @Test
    void userFilterNarrowsToThatUserOnly() throws Exception {
        mockMvc.perform(get("/api/admin/conversations")
                        .param("userId", String.valueOf(userId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(mine));

        mockMvc.perform(get("/api/admin/conversations")
                        .param("userId", String.valueOf(otherUserId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(theirs));
    }

    @Test
    void agentAndDateFiltersWork() throws Exception {
        mockMvc.perform(get("/api/admin/conversations")
                        .param("agentId", String.valueOf(secondAgentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(theirs));

        String yesterday = LocalDate.now().minusDays(1).toString();
        mockMvc.perform(get("/api/admin/conversations")
                        .param("agentId", String.valueOf(agentId))
                        .param("from", yesterday)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        String tomorrow = LocalDate.now().plusDays(1).toString();
        mockMvc.perform(get("/api/admin/conversations")
                        .param("from", tomorrow)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // a date-only `to` includes the whole day (exclusive upper bound at the next midnight)
        mockMvc.perform(get("/api/admin/conversations")
                        .param("agentId", String.valueOf(agentId))
                        .param("to", LocalDate.now().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/admin/conversations")
                        .param("from", "not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void detailReturnsEveryMessageWithItsStepsAndCitations() throws Exception {
        String body = mockMvc.perform(get("/api/admin/conversations/" + mine)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation.id").value(mine))
                .andExpect(jsonPath("$.data.conversation.userName").exists())
                .andExpect(jsonPath("$.data.conversation.messageCount").value(2))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[1].citations.length()").value(1))
                .andExpect(jsonPath("$.data.messages[1].steps.length()").value(2))
                .andExpect(jsonPath("$.data.messages[1].steps[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.data.messages[1].latencyMs").value(1234))
                .andReturn().getResponse().getContentAsString();

        assertEquals("30 天内 [1]。", JsonPath.read(body, "$.data.messages[1].content"));
        assertEquals("第 7 条：报销需在 30 天内提交。",
                JsonPath.read(body, "$.data.messages[1].citations[0].content"),
                "the citation snippet survives the JSONB round-trip");
        assertEquals("生成 1 条检索查询",
                JsonPath.read(body, "$.data.messages[1].steps[0].summary"),
                "summary is unpacked from the payload envelope");
    }

    @Test
    void detailWritesAStructuredAuditLog(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/admin/conversations/" + mine)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        String logs = output.getOut();
        assertTrue(logs.contains("adminAudit"), "an administrator reading a thread must be logged");
        assertTrue(logs.contains("conversationId=" + mine));
        assertTrue(logs.contains("actor="), "the actor must be identifiable in the log line");
    }

    @Test
    void usersAreForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/admin/conversations/" + mine)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // and a USER cannot reach another user's thread through the audit path either
        mockMvc.perform(get("/api/admin/conversations/" + theirs)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownConversationIs404ForAdmins() throws Exception {
        mockMvc.perform(get("/api/admin/conversations/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ------------------------------------------------------------------ helpers

    private String username(long id) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, id);
    }

    private void insertStep(long messageId, String phase, String summary, long latencyMs) {        jdbc.update("""
                INSERT INTO reasoning_steps (message_id, round, phase, payload, latency_ms)
                VALUES (?, 1, ?, jsonb_build_object('summary', ?, 'detail', '{}'), ?)
                """, messageId, phase, summary, latencyMs);
    }

    private String citationsJson() {
        return """
                [{"chunkId":101,"documentId":202,"kbId":%d,"nodeId":null,
                  "docName":"handbook.pdf","nodeName":null,
                  "content":"第 7 条：报销需在 30 天内提交。","score":0.87}]
                """.formatted(kbId);
    }
}
