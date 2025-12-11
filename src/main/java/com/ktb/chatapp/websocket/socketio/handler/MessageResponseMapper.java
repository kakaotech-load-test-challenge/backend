package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    /**
     * Message → MessageResponse 변환 (MongoDB Read = 0)
     */
    public MessageResponse mapToMessageResponse(Message message, Object unused) {

        UserResponse senderResponse = null;
        if (message.getMetadata() != null && message.getMetadata().get("sender") instanceof Map<?, ?> senderMap) {
            senderResponse = UserResponse.builder()
                    .id((String) senderMap.get("id"))
                    .name((String) senderMap.get("name"))
                    .build();
        }

        FileResponse fileResponse = null;
        if (message.getMetadata() != null && message.getMetadata().containsKey("fileUrl")) {
            fileResponse = FileResponse.fromMetadata(message.getMetadata());
        }

        MessageResponse.MessageResponseBuilder builder = MessageResponse.builder()
                .id(message.getId())
                .content(message.getContent())
                .type(message.getType())
                .timestamp(message.toTimestampMillis())
                .roomId(message.getRoomId())
                .sender(senderResponse)
                .file(fileResponse)
                .reactions(message.getReactions() != null ? message.getReactions() : new HashMap<>())
                .readers(message.getReaders() != null ? message.getReaders() : new ArrayList<>())
                .metadata(message.getMetadata());

        return builder.build();
    }
}