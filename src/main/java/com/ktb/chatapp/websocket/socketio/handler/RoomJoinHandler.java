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
    private final MessageResponseMapper messageResponseMapper;
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

            // 유저 확인
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }

            // 방 확인
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 이미 참여 중이면 빠르게 리턴
            if (userRooms.isInRoom(userId, roomId)) {
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            // 방 참여자 목록에 추가
            room.getParticipantIds().add(userId);
            roomRepository.save(room);

            // socket.io join
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            // system 입장 메시지 생성
            Message joinMessage = Message.builder()
                    .roomId(roomId)
                    .type(MessageType.system)
                    .content(userName + "님이 입장하였습니다.")
                    .timestamp(LocalDateTime.now())
                    .mentions(new ArrayList<>())
                    .readers(new ArrayList<>())
                    .metadata(new HashMap<>())
                    .isDeleted(false)
                    .reactions(new HashMap<>())
                    .build();

            joinMessage = messageRepository.save(joinMessage);

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse fetched = messageLoader.loadMessages(req, userId);

            // 참가자 목록
            room = roomRepository.findById(roomId).orElse(null);
            List<UserResponse> participants =
                    room.getParticipantIds().stream()
                            .map(userRepository::findById)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(UserResponse::from)
                            .toList();

            // 클라이언트에게 성공 응답
            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(fetched.getMessages())
                    .hasMore(fetched.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 방 전체 브로드캐스트: system join 메시지
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

            // 참가자 목록 업데이트
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