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
 * Conversation and message endpoints (spec §12 {@code chat}).
 *
 * <p>Everything here is open to any authenticated user; ownership is enforced in the service, where
 * a foreign conversation is a 404. The SSE endpoint returns an {@link SseEmitter} instead of the
 * {@code ApiResponse} envelope — a stream cannot be wrapped in {@code {code,message,data}}. Errors
 * raised before the emitter is created (bad request, foreign conversation, 409 busy) still come back
 * as the normal JSON envelope with the matching HTTP status.</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationResponse>> listConversations(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            CurrentUser user) {
        return ApiResponse.ok(service.listConversations(user, page, size));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> createConversation(
            @Valid @RequestBody CreateConversationRequest body, CurrentUser user) {
        return ApiResponse.ok(service.createConversation(body.agentId(), user.id(), body.title()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageSummaryResponse>> listMessages(@PathVariable Long id,
                                                                 CurrentUser user) {
        return ApiResponse.ok(service.listMessages(id, user));
    }

    /** The authoritative answer view, including persisted steps and citations (reconnect path). */
    @GetMapping("/messages/{id}")
    public ApiResponse<MessageDetailResponse> getMessage(@PathVariable Long id, CurrentUser user) {
        return ApiResponse.ok(service.getMessage(id, user));
    }

    @PostMapping(value = "/conversations/{id}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable Long id, @Valid @RequestBody SendMessageRequest body,
                          CurrentUser user) {
        return service.start(id, user, body.content());
    }
}
