package com.advisory.agent;

import com.advisory.chat.orchestration.Phase;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.support.AdvisoryFixture;
import com.advisory.support.TestChatModelGatewayConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 13：{@code POST /api/agents/{id}/test}——试跑一次，返回完整轨迹但不留下任何写入。
 *
 * <p>关键断言是那张行数表：spec §12 要求试跑复用同一套编排，但绝不能碰
 * {@code conversations / messages / reasoning_steps}。日后若有人把试跑改成走
 * {@code ChatService}，本用例会立刻失败。</p>
 */
class AgentTestRunTest extends AdvisoryFixture {

    @MockitoBean
    private RetrievalService retrievalService;

    @Test
    void trialRunReturnsTraceWithoutPersistingAnything() throws Exception {
        stubEvidence("第 7 条：报销需在 30 天内提交。");
        long conversationsBefore = countRows("conversations");
        long messagesBefore = countRows("messages");
        long stepsBefore = countRows("reasoning_steps");

        String body = mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"报销时限是多久？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(TestChatModelGatewayConfig.STUB_ANSWER))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.steps.length()").value(4))
                .andExpect(jsonPath("$.data.steps[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.data.steps[3].phase").value("ANSWER"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse().getContentAsString();

        assertEquals("第 7 条：报销需在 30 天内提交。",
                JsonPath.read(body, "$.data.citations[0].content"),
                "the trace carries the evidence the run actually used");
        assertNotNull(JsonPath.read(body, "$.data.steps[1].payload"), "each step keeps its payload");

        assertEquals(conversationsBefore, countRows("conversations"), "a trial run creates no thread");
        assertEquals(messagesBefore, countRows("messages"), "a trial run stores no message");
        assertEquals(stepsBefore, countRows("reasoning_steps"), "a trial run stores no reasoning step");
    }

    @Test
    void trialRunCanPinAnOlderVersionAndShowsWhichOneItRan() throws Exception {
        stubEvidence("第 7 条：报销需在 30 天内提交。");
        mockMvc.perform(patch("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"你是第二版助手。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(2));

        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\",\"version\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void onlyAdminsCanTrialRun() throws Exception {
        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void blankQuestionIsRejectedBeforeTheModelIsCalled() throws Exception {
        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unknownAgentIs404() throws Exception {
        mockMvc.perform(post("/api/agents/999999/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void zeroEvidenceShortCircuitsJustLikeARealConversation() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of());

        mockMvc.perform(post("/api/agents/" + agentId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"知识库里没有的问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer")
                        .value(com.advisory.chat.orchestration.ChatOrchestrator.NO_EVIDENCE_ANSWER))
                .andExpect(jsonPath("$.data.steps[3].phase").value(Phase.ANSWER.name()));
    }

    private void stubEvidence(String content) {
        when(retrievalService.retrieve(any())).thenReturn(List.of(chunk(content)));
    }
}
