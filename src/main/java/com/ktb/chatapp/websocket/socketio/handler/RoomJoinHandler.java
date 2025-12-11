package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomRequest;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
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
import com.ktb.chatapp.service.RoomPasswordService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final ChatDataStore chatDataStore;
    private final RoomPasswordService roomPasswordService;

    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, JoinRoomRequest request) {
        try {

            SocketUser socketUser = client.get("user");
            if (socketUser == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "세션 만료"));
                return;
            }

            String userId = socketUser.id();
            String userName = socketUser.name();
            String roomId = request.getRoomId();
            String password = request.getPassword();

            if (!sessionService.validateSession(userId, socketUser.authSessionId()).isValid()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "세션 만료"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            String authKey = "room:auth:" + roomId + ":" + userId;
            boolean authorized = chatDataStore.get(authKey, Boolean.class).orElse(false);

            // 인증되지 않은 경우 → 비밀번호 검증
            if (!authorized) {

                // 비밀번호 방인데 비밀번호 없거나 틀리면 튕김
                if (room.isHasPassword() &&
                        (password == null || !roomPasswordService.matches(room, password))) {

                    client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "비밀번호가 올바르지 않습니다."));
                    client.disconnect();
                    return;
                }

                // 인증 성공 → Redis/Local 캐싱
                chatDataStore.set(authKey, true);
            }

            if (userRooms.isInRoom(userId, roomId)) {
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            userRooms.add(userId, roomId);

            // 실제 소켓 join
            client.joinRoom(roomId);

            Message joinMessage = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .content(userName + "님이 입장하였습니다.")
                    .timestamp(LocalDateTime.now())
                    .metadata(new HashMap<>())
                    .build();

            joinMessage = messageRepository.save(joinMessage);

            FetchMessagesRequest fetchRequest = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse fetched = messageLoader.loadMessages(fetchRequest, userId);

            List<UserResponse> participants = userRepository.findByIdIn(room.getParticipantIds())
                    .stream()
                    .map(UserResponse::from)
                    .collect(Collectors.toList());

            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(fetched.getMessages())
                    .hasMore(fetched.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, messageService.toResponse(joinMessage));

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {}, messages={}, more={}",
                    userName, roomId, fetched.getMessages().size(), fetched.isHasMore());

        } catch (Exception e) {
            log.error("JOIN_ROOM 처리 중 오류", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of("message",
                    e.getMessage() != null ? e.getMessage() : "채팅방 입장 오류"));
        }
    }
}