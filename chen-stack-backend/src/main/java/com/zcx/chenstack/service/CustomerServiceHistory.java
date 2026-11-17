package com.zcx.chenstack.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(prefix = "chen.ai.chat-history", name = "enabled", havingValue = "true")
public class CustomerServiceHistory {
    private static final CustomerServiceEvent DONE = CustomerServiceEvent.state("DONE", null, null, null);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public CustomerServiceHistory(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.json = json;
    }

    public record Session(String chatId, String originPath, LocalDateTime updatedAt) {}
    public record Message(long id, String role, String content, LocalDateTime createdAt, List<CustomerServiceEvent> events) {}
    public record Page(List<Message> messages, Long before) {}
    private record Turn(long sessionId, boolean created, Message assistant) {}

    public List<Session> sessions(int userId, int offset) {
        return jdbc.query("""
                SELECT chat_key,origin_path,updated_at FROM ai_chat_session WHERE user_id=?
                ORDER BY updated_at DESC,id DESC LIMIT 25 OFFSET ?
                """, (rs, row) -> new Session(rs.getString(1), rs.getString(2),
                rs.getObject(3, LocalDateTime.class)), userId, offset);
    }

    public Page messages(int userId, String chatId, long before) {
        List<Message> rows = jdbc.query("""
                SELECT m.id,m.role,m.content,m.created_at,m.events FROM ai_chat_session s
                JOIN ai_chat_message m ON m.session_id=s.id
                WHERE s.user_id=? AND s.chat_key=? AND m.id<? AND (m.role='USER' OR m.events IS NOT NULL)
                ORDER BY m.id DESC LIMIT 41
                """, (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class), readEvents(rs.getString(5))), userId, chatId, before);
        boolean more = rows.size() > 40;
        List<Message> page = new ArrayList<>(rows.subList(0, Math.min(rows.size(), 40)));
        Long next = more ? page.getLast().id() : null;
        Collections.reverse(page);
        return new Page(page, next);
    }

    public List<org.springframework.ai.chat.messages.Message> context(int userId, String chatId) {
        List<org.springframework.ai.chat.messages.Message> messages = jdbc.query("""
                SELECT m.role,LEFT(m.content,1500) FROM ai_chat_session s JOIN ai_chat_message m ON m.session_id=s.id
                WHERE s.user_id=? AND s.chat_key=? AND EXISTS (
                    SELECT 1 FROM ai_chat_message a WHERE a.session_id=s.id AND a.request_id=m.request_id
                      AND a.role='ASSISTANT' AND a.events IS NOT NULL AND a.content<>''
                ) ORDER BY m.id DESC LIMIT 8
                """, (rs, row) -> "USER".equals(rs.getString(1))
                ? new org.springframework.ai.chat.messages.UserMessage(rs.getString(2))
                : new org.springframework.ai.chat.messages.AssistantMessage(rs.getString(2)), userId, chatId);
        Collections.reverse(messages);
        return messages;
    }

    public Flux<CustomerServiceEvent> record(int userId, String chatId, String requestId, String origin, String question,
                                              Supplier<Flux<CustomerServiceEvent>> answer) {
        String key = requestId == null ? UUID.randomUUID().toString() : requestId;
        return Mono.fromCallable(() -> begin(userId, chatId, key, origin, question))
                .subscribeOn(Schedulers.boundedElastic()).flatMapMany(turn -> {
                    if (!turn.created()) {
                        return replay(turn.assistant());
                    }
                    StringBuilder text = new StringBuilder();
                    List<CustomerServiceEvent> events = new ArrayList<>();
                    return answer.get().concatMap(event -> {
                        if ("DONE".equals(event.type())) {
                            return Mono.fromRunnable(() -> finish(turn.sessionId(), key, text.toString(), events))
                                    .subscribeOn(Schedulers.boundedElastic()).thenReturn(event);
                        }
                        if ("TEXT".equals(event.type())) {
                            int length = Math.min(event.text().length(), Math.max(0, 16000 - text.length()));
                            text.append(event.text(), 0, length);
                        } else if (events.size() < 32) {
                            events.add(event);
                        }
                        return Mono.just(event);
                    });
                }).onErrorResume(error -> Flux.just(CustomerServiceEvent.failure("SERVICE_UNAVAILABLE",
                        "客服消息暂无法保存，请稍后重试。", null), DONE));
    }

    private Turn begin(int userId, String chatId, String requestId, String origin, String question) {
        return transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO ai_chat_session(user_id,chat_key,origin_path) VALUES (?,?,?)
                    ON DUPLICATE KEY UPDATE id=id
                    """, userId, chatId, safeOrigin(origin));
            long session = jdbc.queryForObject("SELECT id FROM ai_chat_session WHERE user_id=? AND chat_key=? FOR UPDATE",
                    Long.class, userId, chatId);
            List<String> known = jdbc.queryForList("""
                    SELECT content FROM ai_chat_message WHERE session_id=? AND request_id=? AND role='USER'
                    """, String.class, session, requestId);
            if (!known.isEmpty()) {
                if (!question.equals(known.getFirst())) {
                    throw new IllegalStateException("客服请求已使用");
                }
                List<Message> replies = jdbc.query("""
                        SELECT id,role,content,created_at,events FROM ai_chat_message
                        WHERE session_id=? AND request_id=? AND role='ASSISTANT' AND events IS NOT NULL
                        """, (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), readEvents(rs.getString(5))), session, requestId);
                return new Turn(session, false, replies.stream().findFirst().orElse(null));
            }
            jdbc.update("""
                    INSERT INTO ai_chat_message(session_id,request_id,role,content)
                    VALUES (?,?,'USER',?),(?,?,'ASSISTANT','')
                    """, session, requestId, question, session, requestId);
            jdbc.update("UPDATE ai_chat_session SET updated_at=NOW(6) WHERE id=?", session);
            return new Turn(session, true, null);
        });
    }

    private void finish(long session, String request, String text, List<CustomerServiceEvent> events) {
        try {
            jdbc.update("""
                    UPDATE ai_chat_message SET content=?,events=?
                    WHERE session_id=? AND request_id=? AND role='ASSISTANT' AND events IS NULL
                    """, text, json.writeValueAsString(events), session, request);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("客服事件保存失败");
        }
    }

    private List<CustomerServiceEvent> readEvents(String payload) {
        if (payload == null) {
            return List.of();
        }
        try {
            return json.readValue(payload, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("客服事件读取失败");
        }
    }

    private static Flux<CustomerServiceEvent> replay(Message assistant) {
        if (assistant == null) {
            return Flux.just(CustomerServiceEvent.failure("SERVICE_UNAVAILABLE", "消息已收到，请稍后刷新对话。", null), DONE);
        }
        return Flux.concat(assistant.content().isBlank() ? Flux.empty() : Flux.just(CustomerServiceEvent.text(assistant.content())),
                Flux.fromIterable(assistant.events()), Flux.just(DONE));
    }

    private static String safeOrigin(String origin) {
        return origin != null && origin.matches("/(?:[A-Za-z0-9_/-]{0,399})") && !origin.contains("//") ? origin : "/";
    }
}
