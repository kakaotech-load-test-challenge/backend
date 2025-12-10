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
    private final MessageResponseMapper messageResponseMapper;
    private final SessionService sessionService;

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

            // 이미 방에 없으면 무시
            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} is not in room {}", userId, roomId);
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                log.warn("Room {} does not exist", roomId);
                return;
            }

            // 유저 DB 확인
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User {} not found", userId);
                return;
            }

            // 참여자 제거
            room.getParticipantIds().remove(userId);
            roomRepository.save(room);

            // 소켓에서 leave
            client.leaveRoom(roomId);
            userRooms.remove(userId, roomId);

            log.info("User {} left room {}", userName, room.getName());

            // 시스템 메시지 브로드캐스트
            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");

            // 참가자 목록 업데이트
            broadcastParticipantList(roomId);

            // 프론트 알림
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(USER_LEFT, Map.of(
                            "userId", userId,
                            "userName", userName
                    ));

        } catch (Exception e) {
            log.error("Error handling leaveRoom", e);
            client.sendEvent(ERROR, Map.of("message", "채팅방 퇴장 중 오류가 발생했습니다."));
        }
    }

    private void sendSystemMessage(String roomId, String content) {
        try {
            Message msg = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .timestamp(LocalDateTime.now())
                    .content(content)
                    .mentions(new ArrayList<>())
                    .reactions(new HashMap<>())
                    .readers(new ArrayList<>())
                    .metadata(new HashMap<>())
                    .isDeleted(false)
                    .build();

            Message saved = messageRepository.save(msg);
            MessageResponse response = messageResponseMapper.mapToMessageResponse(saved, null);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }

    private void broadcastParticipantList(String roomId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) return;

        var participants = roomOpt.get().getParticipantIds()
                .stream()
                .map(userRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(UserResponse::from)
                .toList();

        socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participants);
    }
}