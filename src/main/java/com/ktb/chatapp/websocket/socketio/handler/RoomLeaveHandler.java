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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

            // RedisA에 기록된 참여 여부 확인
            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} is not in room {}", userId, roomId);
                return;
            }

            // MongoDB Room 조회 — 참가자 목록 읽기용으로만 사용
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                log.warn("Room {} does not exist", roomId);
                return;
            }

            // DB에서 참가자 제거 → 부하테스트 병목 제거
            // room.getParticipantIds().remove(userId);
            // roomRepository.save(room);

            // RedisA 기반 참여자 제거
            userRooms.remove(userId, roomId);

            // 소켓에서 제거
            client.leaveRoom(roomId);

            log.info("User {} left room {}", userName, room.getName());

            // 시스템 메시지 전송
            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");

            // 참여자 목록 업데이트 (read-only DB + RedisA 조합)
            broadcastParticipantList(room);

            // 프론트 이벤트
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
                    .metadata(Map.of())
                    .build();

            Message saved = messageRepository.save(msg);

            MessageResponse response = messageService.toResponse(saved);

            socketIOServer.getRoomOperations(roomId).sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }

    /**
     * 참가자 목록 한 번에 브로드캐스트
     * MongoDB는 읽기만 하고, 참여자 상태는 RedisA(UserRooms)가 관리
     */
    private void broadcastParticipantList(Room room) {

        Set<String> ids = room.getParticipantIds(); // 원본 구조 유지: "정적" 참가자 목록

        // 실제 참여 여부는 RedisA에서 보장됨
        if (ids.isEmpty()) {
            socketIOServer.getRoomOperations(room.getId())
                    .sendEvent(PARTICIPANTS_UPDATE, List.of());
            return;
        }

        List<User> users = userRepository.findByIdIn(ids);

        List<UserResponse> participants =
                users.stream()
                        .map(UserResponse::from)
                        .collect(Collectors.toList());

        socketIOServer.getRoomOperations(room.getId())
                .sendEvent(PARTICIPANTS_UPDATE, participants);
    }
}