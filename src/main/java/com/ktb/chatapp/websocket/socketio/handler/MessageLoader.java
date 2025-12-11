package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.MessageService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final MessageService messageService;
    private final MessageReadStatusService messageReadStatusService;
    private final RedisTemplate<String, Object> redis;

    public MessageLoader(
            MessageRepository messageRepository,
            MessageService messageService,
            MessageReadStatusService messageReadStatusService,
            @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redis
    ) {
        this.messageRepository = messageRepository;
        this.messageService = messageService;
        this.messageReadStatusService = messageReadStatusService;
        this.redis = redis;
    }

    private static final int PAGE_SIZE = 30;
    private static final long CACHE_SECONDS = 30;

    public FetchMessagesResponse loadMessages(FetchMessagesRequest req, String userId) {
        try {
            LocalDateTime before = convertBefore(req.before());
            return loadMessagesInternal(req.roomId(), PAGE_SIZE, before, userId);

        } catch (Exception e) {
            log.error("Error loading messages for room {}", req.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(Collections.emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    /**
     * before == null → 최신 메시지 페이지 요청 → null 반환
     * null을 받으면 캐시 키를 first-page 로 고정하여 캐시 HIT 가능하게 함
     */
    private LocalDateTime convertBefore(Long beforeMillis) {
        if (beforeMillis == null)
            return null;  // 🚀 핵심: now() 반환하면 캐시가 절대 HIT 되지 않음
        return Instant.ofEpochMilli(beforeMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId
    ) {
        String cacheKey = buildCacheKey(roomId, before);

        // 캐시 먼저 확인
        List<Message> cached = (List<Message>) redis.opsForValue().get(cacheKey);

        if (cached != null) {
            asyncUpdateReadStatus(cached, userId);
            return FetchMessagesResponse.builder()
                    .messages(cached.stream().map(messageService::toResponse).toList())
                    .hasMore(cached.size() == PAGE_SIZE)
                    .build();
        }

        // MongoDB 조회 (캐시 MISS)
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage =
                messageRepository.findByRoomIdAndIsDeletedAndTimestampBefore(
                        roomId,
                        false,
                        before == null ? LocalDateTime.now() : before,
                        pageable
                );

        List<Message> messages = messagePage.getContent();

        // 캐시 저장
        redis.opsForValue().set(cacheKey, messages, Duration.ofSeconds(CACHE_SECONDS));

        asyncUpdateReadStatus(messages, userId);

        return FetchMessagesResponse.builder()
                .messages(messages.stream().map(messageService::toResponse).toList())
                .hasMore(messagePage.hasNext())
                .build();
    }

    /**
     * before == null → 항상 동일한 캐시 키(first-page)
     */
    private String buildCacheKey(String roomId, LocalDateTime before) {
        if (before == null) {
            return "cache:messages:room:" + roomId + ":first-page";
        }
        long epoch = before.toInstant(ZoneOffset.UTC).toEpochMilli();
        return "cache:messages:room:" + roomId + ":before:" + epoch;
    }

    @Async
    public CompletableFuture<Void> asyncUpdateReadStatus(List<Message> messages, String userId) {
        try {
            List<String> ids = messages.stream().map(Message::getId).toList();
            messageReadStatusService.updateReadStatus(ids, userId);

        } catch (Exception e) {
            log.error("async updateReadStatus failed", e);
        }
        return CompletableFuture.completedFuture(null);
    }
}