package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.dto.UpdateProfileImageRequest;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Tag(name = "사용자 (Users)", description = "사용자 프로필 관리 API")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
@Slf4j
public class UserController {

    private final UserService userService;

    /**
     * 현재 사용자 프로필 조회
     */
    @Operation(summary = "내 프로필 조회")
    @GetMapping("/profile")
    public ResponseEntity<?> getCurrentUserProfile(Principal principal) {
        try {
            UserResponse response = userService.getCurrentUserProfile(principal.getName());
            return ResponseEntity.ok(response);
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(StandardResponse.error("프로필 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 프로필 수정 (이름, 소개 등)
     */
    @Operation(summary = "내 프로필 수정")
    @PutMapping("/profile")
    public ResponseEntity<?> updateCurrentUserProfile(
            Principal principal,
            @Valid @RequestBody UpdateProfileRequest updateRequest) {

        try {
            UserResponse response = userService.updateUserProfile(principal.getName(), updateRequest);
            return ResponseEntity.ok(response);
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        }
    }

    /**
     * 프로필 이미지 URL 업데이트 (Presigned URL 방식)
     */
    @Operation(summary = "프로필 이미지 URL 저장", description = "프론트가 S3에 업로드 완료 후, 이미지 URL만 전달합니다.")
    @PutMapping("/profile-image")
    public ResponseEntity<?> updateProfileImage(
            Principal principal,
            @Valid @RequestBody UpdateProfileImageRequest request) {

        try {
            UserResponse response = userService.updateProfileImage(principal.getName(), request.getImageUrl());
            return ResponseEntity.ok(StandardResponse.success("프로필 이미지가 업데이트되었습니다.", response));
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(StandardResponse.error("프로필 이미지 업데이트 중 오류"));
        }
    }

    /**
     * 프로필 이미지 삭제
     */
    @Operation(summary = "프로필 이미지 삭제")
    @DeleteMapping("/profile-image")
    public ResponseEntity<?> deleteProfileImage(Principal principal) {
        try {
            userService.updateProfileImage(principal.getName(), ""); // URL 제거
            return ResponseEntity.ok(StandardResponse.success("프로필 이미지가 삭제되었습니다."));
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(StandardResponse.error("삭제 중 오류"));
        }
    }

    /**
     * 회원 탈퇴
     */
    @Operation(summary = "회원 탈퇴")
    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(Principal principal) {
        try {
            userService.deleteUserAccount(principal.getName());
            return ResponseEntity.ok(StandardResponse.success("회원 탈퇴가 완료되었습니다."));
        } catch (UsernameNotFoundException e) {
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        }
    }
}