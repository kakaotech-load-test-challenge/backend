package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.*;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.ai.AiService;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ChatMessageHandler {

    private final SocketIOServer socketIOServer;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    private final AiService aiService;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final BannedWordChecker bannedWordChecker;
    private final MessageService messageService;

    // Micrometer
    private final MeterRegistry registry;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Timer timer;

    // 세션 검증 캐시
    private final ConcurrentHashMap<String, LocalDateTime> sessionValidationCache = new ConcurrentHashMap<>();

    public ChatMessageHandler(
            SessionService sessionService,
            RateLimitService rateLimitService,
            AiService aiService,
            MessageService messageService,
            BannedWordChecker bannedWordChecker,
            SocketIOServer socketIOServer,
            RoomRepository roomRepository,
            UserRepository userRepository,
            MeterRegistry registry
    ) {
        this.sessionService = sessionService;
        this.rateLimitService = rateLimitService;
        this.aiService = aiService;
        this.messageService = messageService;
        this.bannedWordChecker = bannedWordChecker;
        this.socketIOServer = socketIOServer;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;

        this.registry = registry;

        this.successCounter = registry.counter("socketio.messages.success");
        this.errorCounter = registry.counter("socketio.messages.error");
        this.timer = registry.timer("socketio.messages.time");
    }

    @OnEvent(CHAT_MESSAGE)
    public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {

        Timer.Sample sample = Timer.start(registry);

        try {
            if (!validateRequest(client, data)) return;

            SocketUser socketUser = client.get("user");
            String userId = socketUser.id();

            if (!validateSessionCached(userId, socketUser.authSessionId(), client)) return;

            if (!checkRateLimit(socketUser, client)) return;

            User sender = userRepository.findById(userId).orElse(null);
            if (sender == null) {
                sendError(client, "MESSAGE_ERROR", "사용자를 찾을 수 없습니다.");
                errorCounter.increment();
                return;
            }

            Room room = validateRoomAccess(data.getRoom(), userId, client);
            if (room == null) {
                errorCounter.increment();
                return;
            }

            MessageContent content = data.getParsedContent();
            if (bannedWordChecker.containsBannedWord(content.getTrimmedContent())) {
                sendError(client, "MESSAGE_REJECTED", "금칙어가 포함된 메시지는 전송할 수 없습니다.");
                errorCounter.increment();
                return;
            }

            Message saved = messageService.saveMessage(
                    data.getMessageType(),
                    data.getRoom(),
                    userId,
                    content,
                    data.getFileData()
            );

            if (saved == null) return;

            MessageResponse response = messageService.toResponse(saved);

            socketIOServer.getRoomOperations(data.getRoom())
                    .sendEvent(MESSAGE, response);

            asyncHandleAIMentions(data.getRoom(), userId, content);
            asyncUpdateLastActivity(userId);

            successCounter.increment();
            sample.stop(timer);

        } catch (Exception e) {
            log.error("Message handling error", e);
            errorCounter.increment();
            sendError(client, "MESSAGE_ERROR", "메시지 처리 실패: " + e.getMessage());
            sample.stop(timer);
        }
    }

    private boolean validateRequest(SocketIOClient client, ChatMessageRequest data) {
        if (data == null) {
            sendError(client, "MESSAGE_ERROR", "메시지 데이터가 없습니다.");
            return false;
        }
        if (client.get("user") == null) {
            sendError(client, "SESSION_EXPIRED", "세션이 만료되었습니다.");
            return false;
        }
        return true;
    }

    private boolean validateSessionCached(String userId, String sessionId, SocketIOClient client) {
        LocalDateTime lastValidated = sessionValidationCache.get(userId);

        if (lastValidated != null &&
                lastValidated.isAfter(LocalDateTime.now().minusSeconds(30))) {
            return true;
        }

        var result = sessionService.validateSession(userId, sessionId);

        if (!result.isValid()) {
            sendError(client, "SESSION_EXPIRED", "세션이 만료되었습니다.");
            return false;
        }

        sessionValidationCache.put(userId, LocalDateTime.now());
        return true;
    }

    private boolean checkRateLimit(SocketUser user, SocketIOClient client) {
        var res = rateLimitService.checkRateLimit(user.id(), 10000, Duration.ofMinutes(1));

        if (!res.allowed()) {
            sendError(client, "RATE_LIMIT_EXCEEDED",
                    "전송 제한 초과", Map.of("retryAfter", res.retryAfterSeconds()));
            return false;
        }
        return true;
    }

    private Room validateRoomAccess(String roomId, String userId, SocketIOClient client) {
        Room room = roomRepository.findById(roomId).orElse(null);

        if (room == null || !room.getParticipantIds().contains(userId)) {
            sendError(client, "MESSAGE_ERROR", "채팅방 접근 권한이 없습니다.");
            return null;
        }
        return room;
    }

    // 전용 스레드풀에서 실행
    @Async("chatTaskExecutor")
    public void asyncHandleAIMentions(String roomId, String userId, MessageContent content) {
        aiService.handleAIMentions(roomId, userId, content);
    }

    @Async("chatTaskExecutor")
    public void asyncUpdateLastActivity(String userId) {
        sessionService.updateLastActivity(userId);
    }

    private void sendError(SocketIOClient client, String code, String message) {
        client.sendEvent(ERROR, Map.of("code", code, "message", message));
    }

    private void sendError(SocketIOClient client, String code, String message, Map<String, Object> extra) {
        Map<String, Object> err = new ConcurrentHashMap<>();
        err.put("code", code);
        err.put("message", message);
        err.putAll(extra);
        client.sendEvent(ERROR, err);
    }
}