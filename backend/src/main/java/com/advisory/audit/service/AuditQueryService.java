package com.advisory.audit.service;

import com.advisory.audit.dto.AuditConversationDetail;
import com.advisory.audit.dto.AuditConversationRow;
import com.advisory.audit.repo.AuditRepository;
import com.advisory.auth.security.CurrentUser;
import com.advisory.chat.api.dto.MessageDetailResponse;
import com.advisory.chat.repo.MessageRepository;
import com.advisory.chat.repo.ReasoningStepRepository;
import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The administrator's read-only view of every conversation.
 *
 * <p>Read-only is the whole contract (spec §8): an ADMIN may audit what users asked and what the
 * agent answered, but may not post on a user's behalf or alter history — there is no write method
 * here and no write endpoint in {@link com.advisory.audit.api.AuditController}.</p>
 *
 * <p>Opening a thread writes one structured log line ({@code adminAudit actor=… conversationId=…}).
 * That is the audit trail for the audit page itself: who read whose conversation, and when, ends up
 * in the application log without needing an extra table for phase one.</p>
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditRepository repository;
    private final MessageRepository messages;
    private final ReasoningStepRepository steps;

    public AuditQueryService(AuditRepository repository, MessageRepository messages,
                             ReasoningStepRepository steps) {
        this.repository = repository;
        this.messages = messages;
        this.steps = steps;
    }

    /**
     * One page of conversations, newest first. {@code from} is inclusive and {@code to} exclusive;
     * a date-only value is interpreted in the server's zone, and a date-only {@code to} means the
     * whole day (it resolves to the next midnight). A full timestamp passed as {@code to} is still
     * exclusive, so a client building "up to and including this instant" must add one millisecond.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditConversationRow> list(Long userId, Long agentId, String from, String to,
                                                   int page, int size) {
        Instant fromInstant = parseTime(from, false);
        Instant toInstant = parseTime(to, true);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        List<AuditConversationRow> items = repository.findPage(userId, agentId, fromInstant, toInstant,
                pageSize, pageIndex * pageSize);
        return PageResponse.of(items, pageIndex, pageSize,
                repository.count(userId, agentId, fromInstant, toInstant));
    }

    /**
     * One thread with every message, its persisted steps and its citations. Steps are fetched per
     * message (N+1 on a conversation that holds a handful of answers) — cheaper to read than a
     * grouped query and irrelevant at this scale.
     */
    @Transactional(readOnly = true)
    public AuditConversationDetail detail(long conversationId, CurrentUser actor) {
        AuditConversationRow row = repository.findRow(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Conversation not found"));
        String actorName = repository.findUsername(actor.id()).orElse("unknown");
        log.info("adminAudit actor={} (id={}) conversationId={} ownerId={}",
                actorName, actor.id(), conversationId, row.userId());

        List<MessageDetailResponse> detail = messages.findByConversation(conversationId).stream()
                .map(message -> MessageDetailResponse.of(message, steps.findByMessage(message.id())))
                .toList();
        return new AuditConversationDetail(row, detail);
    }

    /**
     * Accepts an epoch-millisecond string, an ISO-8601 instant / offset date-time, a local date-time
     * (server zone) or a plain {@code yyyy-MM-dd}. The leniency is for the UI: a date picker sends a
     * date, {@code Date#toISOString} sends a {@code Z} instant, and both must work.
     */
    static Instant parseTime(String raw, boolean upperBound) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.chars().allMatch(Character::isDigit) && value.length() >= 10) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            if (value.length() == 10 && value.charAt(4) == '-') {
                LocalDate date = LocalDate.parse(value);
                LocalDateTime boundary = upperBound
                        ? date.plusDays(1).atStartOfDay()
                        : date.atStartOfDay();
                return boundary.atZone(ZoneId.systemDefault()).toInstant();
            }
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "from/to must be an ISO-8601 date or date-time, got '" + value + "'");
        }
    }
}
