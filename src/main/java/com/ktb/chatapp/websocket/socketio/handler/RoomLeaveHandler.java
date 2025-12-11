package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;

import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class RoomLeaveHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final SessionService sessionService;
    private final MessageService messageService;

    @OnEvent(LEAVE_ROOM)
    public void handleLeaveRoom(SocketIOClient client, String roomId) {

        try {
            SocketUser socketUser = client.get("user");
            if (socketUser == null) {
                client.sendEvent(ERROR, Map.of("message", "세션이 만료되었습니다."));
                return;
            }

            String userId = socketUser.id();
            String userName = socketUser.name();

            // 세션 검증
            if (!sessionService.validateSession(userId, socketUser.authSessionId()).isValid()) {
                client.sendEvent(ERROR, Map.of("message", "세션이 만료되었습니다."));
                return;
            }

            // 방에 없는 유저면 무시
            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} is not in room {}", userId, roomId);
                return;
            }

            // Room 1회 조회
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                log.warn("Room {} does not exist", roomId);
                return;
            }

            // 참가자 제거
            room.getParticipantIds().remove(userId);
            roomRepository.save(room);

            // 소켓에서 제거
            client.leaveRoom(roomId);
            userRooms.remove(userId, roomId);

            log.info("User {} left room {}", userName, room.getName());

            // 시스템 메시지 전송
            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");

            // 참여자 목록 업데이트
            broadcastParticipantList(room);

            // 프론트에 USER_LEFT 이벤트 전달
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(USER_LEFT, Map.of("userId", userId, "userName", userName));

        } catch (Exception e) {
            log.error("Error handling leaveRoom", e);
            client.sendEvent(ERROR, Map.of("message", "채팅방 퇴장 중 오류가 발생했습니다."));
        }
    }

    /**
     * 시스템 메시지 생성 → MessageService.toResponse 사용
     */
    private void sendSystemMessage(String roomId, String content) {
        try {
            Message msg = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .timestamp(LocalDateTime.now())
                    .content(content)
                    .metadata(new HashMap<>()) // file 없음
                    .build();

            Message saved = messageRepository.save(msg);

            MessageResponse response = messageService.toResponse(saved);

            socketIOServer.getRoomOperations(roomId).sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }

    /**
     * 참가자 목록 한 번에 불러와서 브로드캐스트
     */
    private void broadcastParticipantList(Room room) {

        Set<String> ids = room.getParticipantIds();  // Set 그대로 사용 (성능 ↑)
        if (ids.isEmpty()) {
            socketIOServer.getRoomOperations(room.getId())
                    .sendEvent(PARTICIPANTS_UPDATE, List.of());
            return;
        }

        // findByIdIn — DB 1회 조회
        List<User> users = userRepository.findByIdIn(ids);

        List<UserResponse> participants =
                users.stream()
                        .map(UserResponse::from)
                        .toList();

        socketIOServer.getRoomOperations(room.getId())
                .sendEvent(PARTICIPANTS_UPDATE, participants);
    }
}