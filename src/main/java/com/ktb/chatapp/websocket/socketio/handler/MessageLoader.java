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

    private LocalDateTime convertBefore(Long beforeMillis) {
        if (beforeMillis == null) return LocalDateTime.now();
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

        List<Message> cached = (List<Message>) redis.opsForValue().get(cacheKey);

        if (cached != null) {
            asyncUpdateReadStatus(cached, userId);
            return FetchMessagesResponse.builder()
                    .messages(cached.stream().map(messageService::toResponse).toList())
                    .hasMore(cached.size() == PAGE_SIZE)
                    .build();
        }

        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage =
                messageRepository.findByRoomIdAndIsDeletedAndTimestampBefore(
                        roomId,
                        false,
                        before,
                        pageable
                );

        List<Message> messages = messagePage.getContent();

        redis.opsForValue().set(cacheKey, messages, Duration.ofSeconds(CACHE_SECONDS));

        asyncUpdateReadStatus(messages, userId);

        return FetchMessagesResponse.builder()
                .messages(messages.stream().map(messageService::toResponse).toList())
                .hasMore(messagePage.hasNext())
                .build();
    }

    private String buildCacheKey(String roomId, LocalDateTime before) {
        if (before == null) {
            return "cache:messages:room:" + roomId + ":latest";
        }
        return "cache:messages:room:" + roomId + ":before:" + before.toInstant(ZoneOffset.UTC).toEpochMilli();
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