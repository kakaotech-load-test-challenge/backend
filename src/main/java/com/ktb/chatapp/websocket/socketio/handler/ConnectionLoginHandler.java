package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.dto.JoinRoomRequest;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            RoomLeaveHandler roomLeaveHandler,
            MeterRegistry meterRegistry) {

        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.roomLeaveHandler = roomLeaveHandler;

        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }

    /**
     * 인증 이후 호출되는 connect 처리
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();

        try {
            // 멀티 서버 중복 로그인 처리
            handleDuplicateLogin(userId, client);

            // 유저 정보를 socket에 저장
            client.set("user", user);

            // RedisA에 현재 연결상태 등록
            connectedUsers.set(userId, user);

            userRooms.get(userId).forEach(roomId -> {
                JoinRoomRequest req = new JoinRoomRequest();
                req.setRoomId(roomId);
                req.setPassword(null); // 재입장에서는 비번 필요 없음 (Redis 인증 사용)

                roomJoinHandler.handleJoinRoom(client, req);
            });

            // 기본 방(알림방 등) 입장
            client.joinRooms(Set.of("user:" + userId, "room-list"));

            log.info("Socket.IO user connected: {} ({}) - Total: {}",
                    getUserName(client), userId, connectedUsers.size());

        } catch (Exception e) {
            log.error("Error handling connect", e);
            client.sendEvent(ERROR, Map.of("message", "연결 처리 중 오류가 발생했습니다."));
        }
    }

    /**
     * 멀티 노드 환경에서 작동하는 중복 로그인 처리
     */
    private void handleDuplicateLogin(String userId, SocketIOClient newClient) {

        SocketUser existing = connectedUsers.get(userId);
        if (existing == null) return;

        var room = socketIOServer.getRoomOperations("user:" + userId);

        room.getClients().forEach(existingClient -> {

            if (existingClient.getSessionId().toString().equals(existing.socketId())) {

                log.info("Duplicate login detected: kicking old session {}", existing.socketId());

                existingClient.sendEvent(DUPLICATE_LOGIN, Map.of(
                        "type", "new_login_attempt",
                        "ipAddress", newClient.getRemoteAddress().toString(),
                        "timestamp", System.currentTimeMillis()
                ));

                existingClient.sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 세션이 종료되었습니다."
                ));

                existingClient.disconnect();
            }
        });
    }

    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {

        String userId = getUserId(client);
        if (userId == null)
            return;

        try {
            String socketId = client.getSessionId().toString();
            SocketUser active = connectedUsers.get(userId);

            if (active != null && active.socketId().equals(socketId)) {
                connectedUsers.del(userId);
            }

            // 유저가 참여한 모든 방 정리
            userRooms.get(userId).forEach(roomId -> roomLeaveHandler.handleLeaveRoom(client, roomId));

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO disconnected: {} ({}) - Total: {}",
                    getUserName(client), userId, connectedUsers.size());

        } catch (Exception e) {
            log.error("Error during onDisconnect", e);
            client.sendEvent(ERROR, Map.of("message", "연결 종료 중 오류가 발생했습니다."));
        }
    }

    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
}