package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FileMessagePayload;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileMessageHandler {

    private final MessageService messageService;

    @OnEvent("send_file")
    public void handleFileMessage(SocketIOClient client, Map<String, Object> payload) {

        log.info("Received file payload: {}", payload);

        // 기본 정보
        String roomId = (String) payload.get("roomId");
        String userId = client.get("userId"); // 소켓 인증에서 저장된 userId

        // fileData: {url, mimeType, originalName, size}
        Map<String, Object> fileData = (Map<String, Object>) payload.get("fileData");

        // 파일 메시지는 텍스트 내용이 없으므로 빈 content 사용
        MessageContent emptyContent = MessageContent.from("");

        // senderSnapshot 구성
        Map<String, Object> senderSnapshot = new HashMap<>();
        senderSnapshot.put("userId", userId);

        // MessageService로 파일 메시지 저장
        Message saved = messageService.saveMessage(
                "file",
                roomId,
                userId,
                emptyContent,
                fileData,
                senderSnapshot
        );

        // 같은 채팅방에 있는 유저들에게 broadcast
        client.getNamespace()
                .getRoomOperations(roomId)
                .sendEvent("new_message", messageService.toResponse(saved));
    }
}