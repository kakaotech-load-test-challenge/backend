package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.websocket.socketio.handler.MessageLoader;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageFetchHandler {

    private final RoomRepository roomRepository;
    private final MessageLoader messageLoader;

    @OnEvent(FETCH_PREVIOUS_MESSAGES)
    public void handleFetchMessages(SocketIOClient client, FetchMessagesRequest data) {

        String userId = getUserId(client);
        if (userId == null) {
            client.sendEvent(ERROR, Map.of(
                    "code", "UNAUTHORIZED",
                    "message", "인증이 필요합니다."
            ));
            return;
        }

        try {
            // 권한 체크
            Room room = roomRepository.findById(data.roomId()).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of(
                        "code", "LOAD_ERROR",
                        "message", "채팅방 접근 권한이 없습니다."
                ));
                return;
            }

            // 안전한 limit 적용 (최대 30)
            int requestedLimit = data.limit();
            int safeLimit = Math.min(requestedLimit > 0 ? requestedLimit : 30, 30);

            FetchMessagesRequest safeRequest =
                    new FetchMessagesRequest(data.roomId(), safeLimit, data.before());

            client.sendEvent(MESSAGE_LOAD_START);

            FetchMessagesResponse result = messageLoader.loadMessages(safeRequest, userId);

            client.sendEvent(PREVIOUS_MESSAGES_LOADED, result);

        } catch (Exception e) {
            log.error("Error handling fetchPreviousMessages", e);
            client.sendEvent(ERROR, Map.of(
                    "code", "LOAD_ERROR",
                    "message", e.getMessage() != null
                            ? e.getMessage()
                            : "이전 메시지를 불러오는 중 오류가 발생했습니다."
            ));
        }
    }

    private String getUserId(SocketIOClient client) {
        Object attr = client.get("user");
        if (attr instanceof SocketUser su) {
            return su.id();
        }
        return null;
    }
}