package com.ktb.chatapp.service;

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
    private final MessageResponseMapper messageResponseMapper;

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

        return messageRepository.save(message);
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

        return messageRepository.save(message);
    }

    /**
     * 응답 변환 (Mongo Read = 0)
     */
    public MessageResponse toResponse(Message message) {
        if (message == null) return null;

        // sender를 다시 조회하지 않음 → message.metadata.sender 사용
        MessageResponse response = messageResponseMapper.mapToMessageResponse(message, null);

        // 파일 응답 포함 (file 조회는 saveFileMessage에서 이미 처리됨 → 여기선 조회 안 함)
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
            case "text" ->
                    saveTextMessage(roomId, userId, messageContent, senderSnapshot);
            case "file" ->
                    saveFileMessage(roomId, userId, messageContent, fileData, senderSnapshot);
            default ->
                    throw new IllegalArgumentException("Unsupported message type: " + messageType);
        };
    }
}