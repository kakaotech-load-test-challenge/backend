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

            // 빠른 재입장: Redis에서 체크
            if (userRooms.isInRoom(userId, roomId)) {
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            // RedisA 기반 방 참여 등록
            userRooms.add(userId, roomId);

            // Socket.IO join
            client.joinRoom(roomId);

            // 시스템 메시지 생성 & 저장
            Message joinMessage = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .content(userName + "님이 입장하였습니다.")
                    .timestamp(LocalDateTime.now())
                    .metadata(new HashMap<>())
                    .build();

            joinMessage = messageRepository.save(joinMessage);

            // 최근 메시지 30개
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse fetched = messageLoader.loadMessages(req, userId);

            // 참여자 목록 조회 (DB 1회)
            Set<String> participantIds = room.getParticipantIds();
            List<User> users = userRepository.findByIdIn(participantIds);

            List<UserResponse> participants = users.stream()
                    .map(UserResponse::from)
                    .collect(Collectors.toList());

            // 성공 응답 전송
            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(fetched.getMessages())
                    .hasMore(fetched.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 시스템 메시지를 방 전체에게 전송
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, messageService.toResponse(joinMessage));

            // 참여자 목록도 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {}, messages={}, more={}",
                    userName, roomId, fetched.getMessages().size(), fetched.isHasMore());

        } catch (Exception e) {
            log.error("JOIN_ROOM 처리 중 오류", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of("message",
                    e.getMessage() != null ? e.getMessage() : "채팅방 입장 중 오류 발생"));
        }
    }
}