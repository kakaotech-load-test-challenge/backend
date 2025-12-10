package com.ktb.chatapp.controller;

import com.ktb.chatapp.annotation.RateLimit;
import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "채팅방 (Rooms)", description = "채팅방 생성 및 관리 API - 채팅방 목록 조회, 생성, 참여, 헬스체크")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    @Value("${spring.profiles.active:production}")
    private String activeProfile;

    // Health Check
    @Operation(summary = "채팅방 서비스 헬스체크")
    @SecurityRequirement(name = "")
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> healthCheck() {
        try {
            HealthResponse healthResponse = roomService.getHealthStatus();

            return ResponseEntity
                    .status(healthResponse.isSuccess() ? 200 : 503)
                    .cacheControl(CacheControl.noCache().mustRevalidate())
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .body(healthResponse);

        } catch (Exception e) {
            log.error("Health check 에러", e);
            return ResponseEntity.status(503)
                    .body(HealthResponse.builder().success(false).build());
        }
    }

    // 방 목록 조회 (페이징)
    @Operation(summary = "채팅방 목록 조회", description = "페이지네이션과 검색 기능 적용")
    @RateLimit
    @GetMapping
    public ResponseEntity<?> getAllRooms(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)", example = "10")
            @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "정렬 필드", example = "createdAt")
            @RequestParam(defaultValue = "createdAt") String sortField,
            @Parameter(description = "정렬 순서 (asc/desc)", example = "desc")
            @RequestParam(defaultValue = "desc") String sortOrder,
            @Parameter(description = "검색어 (채팅방 이름)")
            @RequestParam(required = false) String search,
            Principal principal) {

        try {
            PageRequest req = new PageRequest();
            req.setPage(Math.max(0, page));
            req.setPageSize(Math.min(Math.max(1, pageSize), 50));
            req.setSortField(sortField);
            req.setSortOrder(sortOrder);
            req.setSearch(search);

            RoomsResponse response = roomService.getAllRoomsWithPagination(req, principal.getName());

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)))
                    .header("Last-Modified", java.time.Instant.now().toString())
                    .body(response);

        } catch (Exception e) {
            log.error("방 목록 조회 오류", e);

            ErrorResponse errorResponse = ErrorResponse.builder()
                    .success(false)
                    .message("채팅방 목록 조회 실패")
                    .build();

            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    // 방 생성
    @Operation(summary = "채팅방 생성")
    @PostMapping
    public ResponseEntity<?> createRoom(
            @Valid @RequestBody CreateRoomRequest createRoomRequest,
            Principal principal) {

        try {
            if (createRoomRequest.getName() == null || createRoomRequest.getName().isBlank()) {
                return ResponseEntity.status(400).body(
                        StandardResponse.error("방 이름은 필수입니다.")
                );
            }

            var roomResponse = roomService.createRoomAndReturnResponse(
                    createRoomRequest, principal.getName()
            );

            return ResponseEntity.status(201).body(
                    Map.of("success", true, "data", roomResponse)
            );

        } catch (Exception e) {
            log.error("방 생성 오류", e);

            return ResponseEntity.status(500).body(
                    StandardResponse.error("채팅방 생성 실패")
            );
        }
    }

    // 방 상세 조회
    @Operation(summary = "채팅방 상세 조회")
    @GetMapping("/{roomId}")
    public ResponseEntity<?> getRoomById(
            @PathVariable String roomId,
            Principal principal) {

        try {
            var roomResponse = roomService.getRoomResponseById(roomId, principal.getName());

            if (roomResponse == null) {
                return ResponseEntity.status(404)
                        .body(StandardResponse.error("채팅방을 찾을 수 없습니다."));
            }

            return ResponseEntity.ok(
                    Map.of("success", true, "data", roomResponse)
            );

        } catch (Exception e) {
            log.error("채팅방 상세 조회 오류", e);

            return ResponseEntity.status(500).body(
                    StandardResponse.error("채팅방 조회 실패")
            );
        }
    }

    // 방 참여
    @Operation(summary = "채팅방 참여")
    @PostMapping("/{roomId}/join")
    public ResponseEntity<?> joinRoom(
            @PathVariable String roomId,
            @RequestBody JoinRoomRequest joinRoomRequest,
            Principal principal) {

        try {
            var roomResponse = roomService.joinRoomAndReturnResponse(
                    roomId, joinRoomRequest.getPassword(), principal.getName()
            );

            if (roomResponse == null) {
                return ResponseEntity.status(404)
                        .body(StandardResponse.error("채팅방을 찾을 수 없습니다."));
            }

            return ResponseEntity.ok(
                    Map.of("success", true, "data", roomResponse)
            );

        } catch (RuntimeException e) {
            if (e.getMessage().contains("비밀번호")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(StandardResponse.error("비밀번호가 일치하지 않습니다."));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(StandardResponse.error(e.getMessage()));

        } catch (Exception e) {
            log.error("채팅방 참여 오류", e);

            return ResponseEntity.status(500).body(
                    StandardResponse.error("채팅방 참여 실패")
            );
        }
    }
}