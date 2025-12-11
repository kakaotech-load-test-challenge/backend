package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.handler.MessageResponseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;

    public Message saveTextMessage(String roomId, String userId, MessageContent messageContent) {

        if (messageContent == null || messageContent.isEmpty()) return null;

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setType(MessageType.text);
        message.setTimestamp(LocalDateTime.now());
        message.setContent(messageContent.getTrimmedContent());
        message.setMentions(messageContent.aiMentions());
        message.setMetadata(new HashMap<>());

        return messageRepository.save(message);
    }

    public Message saveFileMessage(
            String roomId,
            String userId,
            MessageContent content,
            Map<String, Object> fileData
    ) {
        if (fileData == null || fileData.get("_id") == null) {
            throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
        }

        String fileId = (String) fileData.get("_id");
        File file = fileRepository.findById(fileId).orElse(null);

        // 권한 확인
        if (file == null || !file.getUserId().equals(userId)) {
            throw new IllegalStateException("파일을 찾을 수 없거나 접근 권한이 없습니다.");
        }

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setType(MessageType.file);
        message.setFileId(fileId);
        message.setContent(content.getTrimmedContent());
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(content.aiMentions());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileType", file.getMimeType());
        metadata.put("fileSize", file.getSize());
        metadata.put("originalName", file.getOriginalName());
        metadata.put("fileUrl", file.getUrl());         // 프론트가 직접 렌더링 가능
        message.setMetadata(metadata);

        return messageRepository.save(message);
    }

    public MessageResponse toResponse(Message message) {

        if (message == null) return null;

        User sender = userRepository.findById(message.getSenderId()).orElse(null);

        MessageResponse response = messageResponseMapper.mapToMessageResponse(message, sender);

        // 파일 응답 포함
        if (message.getFileId() != null) {
            fileRepository.findById(message.getFileId())
                    .map(FileResponse::from)
                    .ifPresent(response::setFile);
        }

        return response;
    }

    public Message saveMessage(
            String messageType,
            String roomId,
            String userId,
            MessageContent messageContent,
            Map<String, Object> fileData
    ) {

        return switch (messageType) {
            case "text" -> saveTextMessage(roomId, userId, messageContent);
            case "file" -> saveFileMessage(roomId, userId, messageContent, fileData);
            default -> throw new IllegalArgumentException("Unsupported message type: " + messageType);
        };
    }
}