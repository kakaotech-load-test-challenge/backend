package com.ktb.chatapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redis;

    private static final int LATEST_LIMIT = 30;

    public MessageService(
            MessageRepository messageRepository,
            ObjectMapper objectMapper,
            @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redis
    ) {
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

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
        if (senderSnapshot != null) metadata.put("sender", senderSnapshot);

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

        updateLatestCache(roomId, saved);
        return saved;
    }

    /**
     * 파일 메시지 저장
     */
    public Message saveFileMessage(
            String roomId,
            String userId,
            MessageContent content,
            Map<String, Object> fileData,
            Map<String, Object> senderSnapshot
    ) {
        if (fileData == null || fileData.get("url") == null)
            throw new IllegalArgumentException("파일 URL이 존재하지 않습니다.");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileUrl", fileData.get("url"));
        metadata.put("mimeType", fileData.getOrDefault("mimeType", "application/octet-stream"));
        metadata.put("originalName", fileData.getOrDefault("originalName", "file"));
        metadata.put("size", fileData.getOrDefault("size", 0));

        if (senderSnapshot != null) metadata.put("sender", senderSnapshot);

        Message message = Message.builder()
                .roomId(roomId)
                .senderId(userId)
                .type(MessageType.file)
                .content(content != null ? content.getTrimmedContent() : null)
                .timestamp(LocalDateTime.now())
                .mentions(content != null ? content.aiMentions() : null)
                .metadata(metadata)
                .build();

        Message saved = messageRepository.save(message);

        updateLatestCache(roomId, saved);
        return saved;
    }

    /**
     * 최신 메시지 캐싱 (RedisB)
     */
    private void updateLatestCache(String roomId, Message newMessage) {
        String key = "cache:messages:room:" + roomId + ":latest";

        try {
            List<Message> latest = (List<Message>) redis.opsForValue().get(key);

            if (latest == null) {
                List<Message> list = new ArrayList<>();
                list.add(newMessage);
                redis.opsForValue().set(key, list, Duration.ofSeconds(30));
                return;
            }

            latest.add(0, newMessage);

            if (latest.size() > LATEST_LIMIT) {
                latest = latest.subList(0, LATEST_LIMIT);
            }

            redis.opsForValue().set(key, latest, Duration.ofSeconds(30));

        } catch (Exception e) {
            log.warn("Failed to update latest Redis cache", e);
        }
    }


    /**
     * Message → DTO 변환
     */
    public MessageResponse toResponse(Message message) {
        if (message == null) return null;

        UserResponse sender = null;
        if (message.getMetadata() != null && message.getMetadata().get("sender") instanceof Map<?,?> map) {
            sender = UserResponse.fromSnapshot((Map<String, Object>) map);
        }

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