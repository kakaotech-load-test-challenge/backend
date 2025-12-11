package com.ktb.chatapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.handler.MessageResponseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final FileRepository fileRepository;
    private final MessageResponseMapper messageResponseMapper;

    // Redis 캐싱 추가
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int RECENT_LIMIT = 50;

    /**
     * 텍스트 메시지 저장 (Mongo Read = 0)
     */
    public Message saveTextMessage(
            String roomId,
            String userId,
            MessageContent messageContent,
            Map<String, Object> senderSnapshot
    ) {
        if (messageContent == null || messageContent.isEmpty()) return null;

        Map<String, Object> metadata = new HashMap<>();
        if (senderSnapshot != null) {
            metadata.put("sender", senderSnapshot); // 반정규화된 sender 정보
        }

        Message message = Message.builder()
                .roomId(roomId)
                .senderId(userId)
                .type(MessageType.text)
                .timestamp(LocalDateTime.now())
                .content(messageContent.getTrimmedContent())
                .mentions(messageContent.aiMentions())
                .metadata(metadata)
                .build();

        Message saved = messageRepository.save(message);

        // Redis 캐시 반영
        cacheRecentMessage(roomId, message);

        return saved;
    }

    /**
     * 파일 메시지 저장 (권한 확인 + 최소 Read 1회)
     */
    public Message saveFileMessage(
            String roomId,
            String userId,
            MessageContent content,
            Map<String, Object> fileData,
            Map<String, Object> senderSnapshot
    ) {
        if (fileData == null || fileData.get("_id") == null) {
            throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
        }

        String fileId = (String) fileData.get("_id");

        // 파일 권한 확인 (Mongo Read = 1회)
        File file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new IllegalStateException("파일을 찾을 수 없거나 접근 권한이 없습니다.");
        }

        // 프리사인 URL 기반 메타데이터
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileType", file.getMimeType());
        metadata.put("fileSize", file.getSize());
        metadata.put("originalName", file.getOriginalName());
        metadata.put("fileUrl", file.getUrl());

        if (senderSnapshot != null) {
            metadata.put("sender", senderSnapshot);
        }

        Message message = Message.builder()
                .roomId(roomId)
                .senderId(userId)
                .type(MessageType.file)
                .fileId(fileId)
                .content(content.getTrimmedContent())
                .timestamp(LocalDateTime.now())
                .mentions(content.aiMentions())
                .metadata(metadata)
                .build();

        Message saved = messageRepository.save(message);

        // Redis 캐시 반영
        cacheRecentMessage(roomId, message);

        return saved;
    }

    /**
     * Redis에 최근 메시지 50개 저장
     */
    private void cacheRecentMessage(String roomId, Message message) {
        try {
            MessageResponse response = messageResponseMapper.mapToMessageResponse(message, null);
            String key = "room:" + roomId + ":messages:recent";

            String json = objectMapper.writeValueAsString(response);

            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, RECENT_LIMIT - 1);
            redisTemplate.expire(key, Duration.ofSeconds(60)); // TTL 60초

        } catch (Exception e) {
            log.warn("Failed to cache recent message", e);
            // 캐싱은 실패해도 서비스는 정상동작해야 함
        }
    }

    /**
     * 응답 변환 (Mongo Read = 0)
     */
    public MessageResponse toResponse(Message message) {
        if (message == null) return null;

        MessageResponse response = messageResponseMapper.mapToMessageResponse(message, null);

        if (message.getMetadata() != null && message.getMetadata().containsKey("fileUrl")) {
            FileResponse fileResponse = FileResponse.fromMetadata(message.getMetadata());
            response.setFile(fileResponse);
        }

        return response;
    }

    /**
     * 메시지 타입별 저장
     */
    public Message saveMessage(
            String messageType,
            String roomId,
            String userId,
            MessageContent messageContent,
            Map<String, Object> fileData,
            Map<String, Object> senderSnapshot
    ) {
        return switch (messageType) {
            case "text" -> saveTextMessage(roomId, userId, messageContent, senderSnapshot);
            case "file" -> saveFileMessage(roomId, userId, messageContent, fileData, senderSnapshot);
            default -> throw new IllegalArgumentException("Unsupported message type: " + messageType);
        };
    }
}