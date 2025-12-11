package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    private final FileRepository fileRepository;

    /**
     * Message 엔티티 → MessageResponse DTO 변환
     */
    public MessageResponse mapToMessageResponse(Message message, User sender) {

        MessageResponse.MessageResponseBuilder builder = MessageResponse.builder()
                .id(message.getId())
                .content(message.getContent())
                .type(message.getType())
                .timestamp(message.toTimestampMillis())
                .roomId(message.getRoomId())
                .reactions(message.getReactions() != null ? message.getReactions() : new HashMap<>())
                .readers(message.getReaders() != null ? message.getReaders() : new ArrayList<>())
                .metadata(message.getMetadata());

        if (sender != null) {
            builder.sender(UserResponse.builder()
                    .id(sender.getId())
                    .name(sender.getName())
                    .email(sender.getEmail())
                    .profileImage(sender.getProfileImage())
                    .build());
        }

        if (message.getFileId() != null) {
            fileRepository.findById(message.getFileId())
                    .map(file -> FileResponse.builder()
                            .id(file.getId())
                            .url(file.getUrl())                   // 파일 URL
                            .originalName(file.getOriginalName()) // 원본 파일명
                            .mimeType(file.getMimeType())         // mime 타입
                            .size(file.getSize())                 // 파일 크기
                            .build())
                    .ifPresent(builder::file);
        }

        return builder.build();
    }
}