package com.ktb.chatapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
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

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int RECENT_LIMIT = 50;

    /**
     * 텍스트 메시지 저장
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
            metadata.put("sender", senderSnapshot);
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
        cacheRecentMessage(roomId, saved);

        return saved;
    }

    /**
     * 파일 메시지 저장
     * (프론트에서 fileData: {url, mimeType, originalName, size})
     */
    public Message saveFileMessage(
            String roomId,
            String userId,
            MessageContent content,
            Map<String, Object> fileData,
            Map<String, Object> senderSnapshot
    ) {
        if (fileData == null || fileData.get("url") == null) {
            throw new IllegalArgumentException("파일 URL이 존재하지 않습니다.");
        }

        String url = (String) fileData.get("url");
        String mimeType = (String) fileData.getOrDefault("mimeType", "application/octet-stream");
        String originalName = (String) fileData.getOrDefault("originalName", "file");
        long size = ((Number) fileData.getOrDefault("size", 0)).longValue();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileUrl", url);
        metadata.put("mimeType", mimeType);
        metadata.put("originalName", originalName);
        metadata.put("size", size);

        if (senderSnapshot != null) {
            metadata.put("sender", senderSnapshot);
        }

        Message message = Message.builder()
                .roomId(roomId)
                .senderId(userId)
                .type(MessageType.file)
                .content(content.getTrimmedContent())
                .timestamp(LocalDateTime.now())
                .mentions(content.aiMentions())
                .metadata(metadata)
                .build();

        Message saved = messageRepository.save(message);
        cacheRecentMessage(roomId, saved);

        return saved;
    }

    /**
     * Redis 최근 메시지 캐싱
     */
    private void cacheRecentMessage(String roomId, Message message) {
        try {
            MessageResponse response = toResponse(message);
            String key = "room:" + roomId + ":messages:recent";

            String json = objectMapper.writeValueAsString(response);

            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, RECENT_LIMIT - 1);
            redisTemplate.expire(key, Duration.ofSeconds(60));

        } catch (Exception e) {
            log.warn("Failed to cache recent message", e);
        }
    }

    /**
     * Message → MessageResponse 직접 변환
     * (MessageResponseMapper 제거됨)
     */
    public MessageResponse toResponse(Message message) {
        if (message == null) return null;

        // senderSnapshot이 metadata.sender에 있을 수도 있음
        UserResponse sender = null;
        if (message.getMetadata() != null && message.getMetadata().get("sender") instanceof Map<?,?> map) {
            sender = UserResponse.fromSnapshot((Map<String, Object>) map);
        }

        // 파일 메시지인 경우 FileResponse 구성
        FileResponse fileResponse = null;
        if (message.getMetadata() != null && message.getMetadata().containsKey("fileUrl")) {
            fileResponse = FileResponse.fromMetadata(message.getMetadata());
        }

        return MessageResponse.builder()
                .id(message.getId())
                .roomId(message.getRoomId())
                .content(message.getContent())
                .sender(sender)
                .type(message.getType())
                .file(fileResponse)
                .aiType(message.getAiType())
                .timestamp(message.toTimestampMillis())
                .reactions(message.getReactions())
                .readers(message.getReaders())
                .build();
    }

    /**
     * messageType 에 따른 분기 처리
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