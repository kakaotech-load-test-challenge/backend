package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.handler.MessageLoader;
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
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageService messageService;
    private final SessionService sessionService;

    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            SocketUser socketUser = client.get("user");
            if (socketUser == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "세션 만료"));
                return;
            }

            String userId = socketUser.id();
            String userName = socketUser.name();

            // 세션 검증
            if (!sessionService.validateSession(userId, socketUser.authSessionId()).isValid()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "세션 만료"));
                return;
            }

            // 방 조회
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // User 조회
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }

            // 이미 참여 중이면 fast return
            if (userRooms.isInRoom(userId, roomId)) {
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            // 방 참여자 추가
            room.getParticipantIds().add(userId);
            roomRepository.save(room);

            // socket.io join
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            // 시스템 메시지 생성
            Message joinMessage = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .content(userName + "님이 입장하였습니다.")
                    .timestamp(LocalDateTime.now())
                    .metadata(new HashMap<>())
                    .build();

            joinMessage = messageRepository.save(joinMessage);

            // 최근 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse fetched = messageLoader.loadMessages(req, userId);

            // 참여자 목록 — findByIdIn() 1회로 최적화
            Set<String> participantIds = room.getParticipantIds();   // ← 여기 Set으로 수정
            List<User> users = userRepository.findByIdIn(participantIds);

            List<UserResponse> participants = users.stream()
                    .map(UserResponse::from)
                    .toList();

            // 클라이언트 응답
            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(fetched.getMessages())
                    .hasMore(fetched.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 시스템 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, messageService.toResponse(joinMessage));

            // 참여자 목록 업데이트
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {}, messages={}, hasMore={}",
                    userName, roomId, fetched.getMessages().size(), fetched.isHasMore());

        } catch (Exception e) {
            log.error("JOIN_ROOM 처리 중 오류", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장 중 오류 발생"
            ));
        }
    }
}