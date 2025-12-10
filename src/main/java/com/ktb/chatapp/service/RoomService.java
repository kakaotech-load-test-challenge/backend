package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRoomsWithPagination(com.ktb.chatapp.dto.PageRequest request,
                                                   String userEmail) {

        try {
            // 정렬 필드 검증
            if (!request.isValidSortField()) request.setSortField("createdAt");
            if (!request.isValidSortOrder()) request.setSortOrder("desc");

            Sort.Direction direction = "desc".equals(request.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            String sortField = request.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds"; // MongoDB 필드명
            }

            // Spring Pageable 생성
            PageRequest pageable = PageRequest.of(
                    request.getPage(),
                    request.getPageSize(),
                    Sort.by(direction, sortField)
            );

            // 검색어 있을 때 / 없을 때 분기
            Page<Room> roomPage;
            if (request.getSearch() != null && !request.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                        request.getSearch().trim(), pageable
                );
            } else {
                roomPage = roomRepository.findAll(pageable);
            }

            // Room → RoomResponse 변환
            List<RoomResponse> responses = roomPage.getContent().stream()
                    .map(room -> buildRoomResponse(room, userEmail))
                    .collect(Collectors.toList());

            // 메타데이터 생성
            PageMetadata meta = PageMetadata.builder()
                    .total(roomPage.getTotalElements())
                    .page(request.getPage())
                    .pageSize(request.getPageSize())
                    .totalPages(roomPage.getTotalPages())
                    .hasMore(roomPage.hasNext())
                    .currentCount(responses.size())
                    .sort(PageMetadata.SortInfo.builder()
                            .field(request.getSortField())
                            .order(request.getSortOrder())
                            .build())
                    .build();

            return RoomsResponse.builder()
                    .success(true)
                    .data(responses)
                    .metadata(meta)
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 중 오류 발생", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long start = System.currentTimeMillis();
            boolean mongoOk = false;
            long latency = 0;

            try {
                roomRepository.findOneForHealthCheck();
                latency = System.currentTimeMillis() - start;
                mongoOk = true;
            } catch (Exception e) {
                log.warn("MongoDB Health Check 실패", e);
            }

            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(mongoOk)
                    .latency(latency)
                    .build());

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health Check 처리 실패", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    public RoomResponse createRoomAndReturnResponse(CreateRoomRequest req, String userEmail) {
        Room room = createRoom(req, userEmail);
        return buildRoomResponse(room, userEmail);
    }

    private Room createRoom(CreateRoomRequest req, String userEmail) {
        User creator = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userEmail));

        Room room = new Room();
        room.setName(req.getName());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        Room saved = roomRepository.save(room);

        // 이벤트 발행
        try {
            RoomResponse response = buildRoomResponse(saved, userEmail);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, response));
        } catch (Exception e) {
            log.error("RoomCreated 이벤트 실패", e);
        }

        return saved;
    }

    public RoomResponse getRoomResponseById(String roomId, String userEmail) {
        return roomRepository.findById(roomId)
                .map(room -> buildRoomResponse(room, userEmail))
                .orElse(null);
    }

    public RoomResponse joinRoomAndReturnResponse(String roomId, String password, String userEmail) {
        Room room = joinRoom(roomId, password, userEmail);
        if (room == null) return null;
        return buildRoomResponse(room, userEmail);
    }

    public Room joinRoom(String roomId, String password, String userEmail) {

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) return null;

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userEmail));

        // 비밀번호 검증
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        if (!room.getParticipantIds().contains(user.getId())) {
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }

        // 이벤트 발행
        try {
            RoomResponse response = buildRoomResponse(room, userEmail);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, response));
        } catch (Exception e) {
            log.error("RoomUpdated 이벤트 실패", e);
        }

        return room;
    }

    private RoomResponse buildRoomResponse(Room room, String userEmail) {
        if (room == null) return null;

        User creator = userRepository.findById(room.getCreator()).orElse(null);

        List<User> participants = room.getParticipantIds().stream()
                .map(userRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        long recentCount = messageRepository.countRecentMessagesByRoomId(
                room.getId(), LocalDateTime.now().minusMinutes(10)
        );

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName())
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.from(creator) : null)
                .participants(
                        participants.stream()
                                .map(UserResponse::from)
                                .collect(Collectors.toList())
                )
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getEmail().equals(userEmail))
                .recentMessageCount((int) recentCount)
                .build();
    }
}