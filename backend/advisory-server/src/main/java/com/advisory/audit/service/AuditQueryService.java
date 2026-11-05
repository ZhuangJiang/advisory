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
 * 管理员对所有会话的只读视图。
 *
 * <p>只读就是全部契约（spec §8）：管理员可以审计用户问了什么、Agent 答了什么，但不能替用户发言或改动历史——
 * 这里没有任何写方法，{@link com.advisory.audit.api.AuditController} 也没有写接口。</p>
 *
 * <p>打开某条会话时会写一行结构化日志（{@code adminAudit actor=… conversationId=…}），这就是审计页自身的
 * 审计线索：谁在什么时候看了谁的会话，全部落在应用日志里，一期不必为此再加一张表。</p>
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
     * 分页查询会话，最新的在前。
     *
     * <p>{@code from} 为闭区间、{@code to} 为开区间；只给日期时按服务器时区解释，且纯日期的 {@code to}
     * 覆盖当天整天（解析为次日零点）。即便传的是完整时间戳，{@code to} 仍是开区间，所以想表达
     * 「包含这一毫秒」的客户端需要自行加 1 毫秒。</p>
     *
     * @param query 筛选与分页条件
     * @return 会话分页数据
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
     * 读取一条会话的全部消息、每条消息落库的步骤与引用。
     *
     * <p>步骤按消息逐条查询（一条会话通常只有几个回答，构成 N+1），在当前规模下比写一条聚合查询更省事，
     * 实际开销也可以忽略。</p>
     *
     * @param conversationId 会话主键
     * @return 会话详情
     */
    @Transactional(readOnly = true)
    public AuditConversationDetail detail(long conversationId, CurrentUser actor) {
        AuditConversationRow row = repository.findRow(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "会话不存在"));
        String actorName = repository.findUsername(actor.id()).orElse("unknown");
        log.info("adminAudit actor={} (id={}) conversationId={} ownerId={}",
                actorName, actor.id(), conversationId, row.userId());

        List<MessageDetailResponse> detail = messages.findByConversation(conversationId).stream()
                .map(message -> MessageDetailResponse.of(message, steps.findByMessage(message.id())))
                .toList();
        return new AuditConversationDetail(row, detail);
    }

    /**
     * 解析时间参数，接受四种写法：毫秒时间戳字符串、ISO-8601 瞬时时间、带偏移的日期时间、本地日期时间
     * （按服务器时区）以及纯 {@code yyyy-MM-dd}。
     *
     * <p>这份宽容是给前端用的：日期选择器只会给日期，而 {@code Date#toISOString} 给的是带 {@code Z} 的瞬时时间，
     * 两种都必须能用，否则同一个筛选功能换个控件就失效。</p>
     *
     * @param raw 原始参数值
     * @return 解析后的时刻
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
                    "from/to 必须是 ISO-8601 日期或日期时间，实际为 '" + value + "'");
        }
    }
}
