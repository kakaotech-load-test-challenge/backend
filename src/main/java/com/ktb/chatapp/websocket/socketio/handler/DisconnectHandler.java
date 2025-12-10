package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisconnectHandler {

    private final UserRooms userRooms;
    private final RoomLeaveHandler roomLeaveHandler;

    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {

        SocketUser user = client.get("user");

        if (user == null) {
            log.debug("Disconnect event — no user session found.");
            return;
        }

        String userId = user.id();
        String userName = user.name();

        Set<String> rooms = userRooms.get(userId);

        log.info("User disconnected: {} (rooms={})", userName, rooms);

        // 각 방에 대해 안전하게 퇴장 처리
        for (String roomId : rooms) {
            try {
                roomLeaveHandler.handleLeaveRoom(client, roomId);
            } catch (Exception e) {
                log.error("Failed to process leaveRoom for user {} in room {}", userId, roomId, e);
            }
        }

        // 메모리 정리
        userRooms.clear(userId);

        log.debug("Cleanup complete for user {}", userId);
    }
}