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

    private static final int PAGE_SIZE = 30;
    private static final long CACHE_SECONDS = 30;

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

    //before == null → 최신 페이지
    //before != null → 이전 메시지 페이지
    private LocalDateTime convertBefore(Long beforeMillis) {
        if (beforeMillis == null) {
            return null;
        }
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
        String cacheKey = buildCacheKey(roomId);

        // 캐시는 "first-page" 에서만 사용
        if (before == null) {
            List<Message> cached = null;
            try {
                cached = (List<Message>) redis.opsForValue().get(cacheKey);
            } catch (Exception e) {
                log.warn("Redis cache get failed, fallback to Mongo. key={}", cacheKey, e);
            }

            if (cached != null) {
                asyncUpdateReadStatus(cached, userId);
                return FetchMessagesResponse.builder()
                        .messages(cached.stream().map(messageService::toResponse).toList())
                        .hasMore(cached.size() == PAGE_SIZE)
                        .build();
            }
        }

        // MongoDB 조회 (항상 정확한 소스)
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage =
                messageRepository.findByRoomIdAndIsDeletedAndTimestampBefore(
                        roomId,
                        false,
                        before == null ? LocalDateTime.now() : before,
                        pageable
                );

        List<Message> messages = messagePage.getContent();

        // 캐시 저장도 first-page만
        if (before == null) {
            try {
                redis.opsForValue().set(cacheKey, messages, Duration.ofSeconds(CACHE_SECONDS));
            } catch (Exception e) {
                log.warn("Redis cache set failed, skip caching. key={}", cacheKey, e);
            }
        }

        asyncUpdateReadStatus(messages, userId);

        return FetchMessagesResponse.builder()
                .messages(messages.stream().map(messageService::toResponse).toList())
                .hasMore(messagePage.hasNext())
                .build();
    }

    //first-page 전용 캐시 키
    private String buildCacheKey(String roomId) {
        return "cache:messages:room:" + roomId + ":first-page";
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