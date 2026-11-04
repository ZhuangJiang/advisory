package com.advisory.chat.api;

import com.advisory.auth.security.CurrentUser;
import com.advisory.chat.api.dto.ConversationResponse;
import com.advisory.chat.api.dto.CreateConversationRequest;
import com.advisory.chat.api.dto.MessageDetailResponse;
import com.advisory.chat.api.dto.MessageSummaryResponse;
import com.advisory.chat.api.dto.SendMessageRequest;
import com.advisory.chat.service.ChatService;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 会话与消息接口（spec §12 的 {@code chat}）。
 *
 * <p>所有接口对已登录用户开放，归属校验放在 Service：访问他人会话一律 404。SSE 接口返回
 * {@link SseEmitter} 而不是 {@code ApiResponse} 信封——流式响应无法塞进 {@code {code,message,data}}。
 * 但在 emitter 创建之前发生的错误（参数非法、会话不属于自己、409 忙）仍按常规 JSON 信封返回，
 * 并带上对应的 HTTP 状态。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    /** 当前用户的会话列表；管理员不过滤，前端据此渲染只读。 */
    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationResponse>> listConversations(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            CurrentUser user) {
        return ApiResponse.ok(service.listConversations(user, page, size));
    }

    /** 新建会话并锁定 Agent 的当前版本。 */
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> createConversation(
            @Valid @RequestBody CreateConversationRequest body, CurrentUser user) {
        return ApiResponse.ok(service.createConversation(body.agentId(), user.id(), body.title()));
    }

    /** 会话内的消息列表，按时间正序。 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageSummaryResponse>> listMessages(@PathVariable Long id,
                                                                 CurrentUser user) {
        return ApiResponse.ok(service.listMessages(id, user));
    }

    /** 答案的权威视图，包含已落库的步骤与引用（断线重连时走这里）。 */
    @GetMapping("/messages/{id}")
    public ApiResponse<MessageDetailResponse> getMessage(@PathVariable Long id, CurrentUser user) {
        return ApiResponse.ok(service.getMessage(id, user));
    }

    @PostMapping(value = "/conversations/{id}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    /** 发起提问并返回 SSE 流；emitter 创建失败时仍按 JSON 信封返回错误。 */
    public SseEmitter ask(@PathVariable Long id, @Valid @RequestBody SendMessageRequest body,
                          CurrentUser user) {
        return service.start(id, user, body.content());
    }
}
