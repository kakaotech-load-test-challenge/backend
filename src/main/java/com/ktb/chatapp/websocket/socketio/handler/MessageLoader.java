package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.MessageService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;

    private final MessageService messageService; // ★ 변환의 단일 진실 소스
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            LocalDateTime before = convertBeforeMillis(data.before());

            return loadMessagesInternal(
                    data.roomId(),
                    data.limit(BATCH_SIZE),
                    before,
                    userId
            );

        } catch (Exception e) {
            log.error("Error loading messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(Collections.emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private LocalDateTime convertBeforeMillis(Long beforeMillis) {
        if (beforeMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(beforeMillis),
                ZoneId.systemDefault()
        );
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId
    ) {

        Pageable pageable = PageRequest.of(
                0,
                limit,
                Sort.by("timestamp").descending()
        );

        Page<Message> messagePage =
                messageRepository.findByRoomIdAndIsDeletedAndTimestampBefore(
                        roomId,
                        false,
                        before,
                        pageable
                );

        List<Message> messages = messagePage.getContent();

        // 읽음 처리 비동기 실행 (DB bulk update)
        asyncUpdateReadStatus(messages, userId);

        // 메시지 변환 — 오직 MessageService만 사용
        List<MessageResponse> responses = messages.stream()
                .map(messageService::toResponse)   // ★ metadata 기반 FileResponse 자동 처리
                .toList();

        return FetchMessagesResponse.builder()
                .messages(responses)
                .hasMore(messagePage.hasNext())
                .build();
    }

    // 읽음 처리 비동기 실행
    @Async
    public CompletableFuture<Void> asyncUpdateReadStatus(List<Message> messages, String userId) {
        try {
            List<String> ids = messages.stream()
                    .map(Message::getId)
                    .toList();

            messageReadStatusService.updateReadStatus(ids, userId);

        } catch (Exception e) {
            log.error("async updateReadStatus failed", e);
        }

        return CompletableFuture.completedFuture(null);
    }
}