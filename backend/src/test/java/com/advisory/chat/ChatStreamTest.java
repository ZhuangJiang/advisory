package com.advisory.chat;

import com.advisory.chat.service.ConversationLock;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.support.TestChatModelGatewayConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12: the SSE stream of one question.
 *
 * <p>{@link RetrievalService} is mocked so the test owns the evidence (a fixed chunk) and the model
 * is the deterministic stub gateway; what is under test is the event protocol and its persistence,
 * not retrieval or prompting.</p>
 */
class ChatStreamTest extends ChatFixture {

    private static final Pattern EVENT = Pattern.compile("(?m)^event:(\\w+)\\s*$");

    @MockitoBean
    private RetrievalService retrievalService;

    @Autowired
    private ConversationLock lock;

    @Test
    void emitsMetaFirstThenStepsThenDone() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(chunk("第 3 条：年假 15 天。")));
        long conversationId = createConversation(userToken);

        MvcResult result = mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"年假有多少天？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(20_000);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> events = eventNames(body);

        assertEquals("meta", events.get(0), "the reconnect handle must be the first frame");
        assertEquals("done", events.get(events.size() - 1), "the stream ends with done");
        assertEquals(8, events.stream().filter("step"::equals).count(),
                "PLAN/RETRIEVE/REFLECT/ANSWER each emit started+finished");
        assertTrue(events.contains("delta"), "the answer must be streamed as a delta");
        assertTrue(events.contains("citations"), "retrieved chunks are pushed before the answer");
        assertTrue(body.contains(TestChatModelGatewayConfig.STUB_ANSWER), "the real answer is in the stream");
        assertTrue(body.contains("第 3 条：年假 15 天。"), "the citation snippet is in the stream");

        long messageId = assistantMessageId(conversationId);
        assertEquals(String.valueOf(messageId), firstMetaMessageId(body),
                "meta carries the persisted assistant message id");
        assertEquals("COMPLETED", messageStatus(messageId));
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM reasoning_steps WHERE message_id = ?", Integer.class, messageId),
                "one row per executed phase is persisted, not only streamed");
        assertEquals(1, jdbc.queryForObject(
                "SELECT jsonb_array_length(citations) FROM messages WHERE id = ?", Integer.class, messageId),
                "citations are stored with the answer");
    }

    @Test
    void secondQuestionWhileOneIsRunningReturns409() throws Exception {
        long conversationId = createConversation(userToken);
        // Simulates the in-flight question: the lock is exactly what start() takes.
        assertTrue(lock.tryAcquire(conversationId));
        try {
            mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"第二个问题\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40906));
        } finally {
            lock.release(conversationId);
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId),
                "a rejected question must not leave a USER message behind");
    }

    @Test
    void blankQuestionIsRejectedBeforeAnyStreamStarts() throws Exception {
        long conversationId = createConversation(userToken);
        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private static List<String> eventNames(String body) {
        List<String> names = new ArrayList<>();
        Matcher matcher = EVENT.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String firstMetaMessageId(String body) {
        int meta = body.indexOf("event:meta");
        Matcher matcher = Pattern.compile("\"messageId\":(\\d+)")
                .matcher(body.substring(meta, Math.min(body.length(), meta + 300)));
        return matcher.find() ? matcher.group(1) : null;
    }
}
