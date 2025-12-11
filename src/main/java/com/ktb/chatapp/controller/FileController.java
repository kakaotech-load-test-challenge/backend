package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.PresignedUrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Tag(name = "파일 (S3 Presigned URL)", description = "S3 프리사인 URL 기반 파일 업로드 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final PresignedUrlService presignedUrlService;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;

    @Operation(summary = "Presigned URL 발급", description = "S3에 직접 업로드할 수 있는 URL을 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "URL 발급 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/presigned-url")
    public StandardResponse<Map<String, Object>> generatePresignedUrl(
            @RequestBody PresignedUrlRequest request,
            Principal principal
    ) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // 파일 key 생성
            String key = "uploads/" + user.getId() + "_" + System.currentTimeMillis() + "_" + request.fileName;

            // PUT 업로드 URL 생성 (프리사인)
            String uploadUrl = presignedUrlService.generateUploadUrl(key, request.contentType);

            // 실제 접근 URL 생성
            String fileUrl = presignedUrlService.getPublicFileUrl(key);

            Map<String, Object> data = new HashMap<>();
            data.put("uploadUrl", uploadUrl);
            data.put("fileUrl", fileUrl);

            return StandardResponse.success("Presigned URL 생성 완료", data);

        } catch (Exception e) {
            log.error("Presigned URL 생성 중 오류", e);
            return StandardResponse.error("Presigned URL 생성 실패");
        }
    }

    @Operation(summary = "파일 정보 저장", description = "S3 업로드 완료 후 파일 정보를 DB에 기록합니다.")
    @PostMapping("/save")
    public StandardResponse<File> saveFileInfo(
            @RequestBody SaveFileRequest request,
            Principal principal
    ) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            File file = new File();
            file.setUserId(user.getId());
            file.setUrl(request.fileUrl);
            file.setOriginalName(request.originalName);
            file.setMimeType(request.mimeType);
            file.setSize(request.size);
            file.setUploadDate(LocalDateTime.now());

            fileRepository.save(file);

            return StandardResponse.success("파일 정보 저장 완료", file);

        } catch (Exception e) {
            log.error("파일 정보 저장 중 오류", e);
            return StandardResponse.error("파일 정보 저장 실패");
        }
    }

    @Operation(summary = "파일 삭제", description = "업로드된 파일을 삭제합니다.")
    @DeleteMapping
    public StandardResponse<?> deleteFile(
            @RequestBody DeleteFileRequest request,
            Principal principal
    ) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            File file = fileRepository.findById(request.fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            // 본인 파일인지 검증
            if (!file.getUserId().equals(user.getId())) {
                return StandardResponse.error("본인이 업로드한 파일만 삭제할 수 있습니다.");
            }

            // S3 객체 삭제
            presignedUrlService.deleteObjectByUrl(file.getUrl());

            // DB에서 삭제
            fileRepository.delete(file);

            return StandardResponse.success("파일 삭제 완료");

        } catch (Exception e) {
            log.error("파일 삭제 중 오류", e);
            return StandardResponse.error("파일 삭제 실패");
        }
    }

    @Data
    public static class PresignedUrlRequest {
        public String fileName;
        public String contentType;
    }

    @Data
    public static class SaveFileRequest {
        public String fileUrl;
        public String originalName;
        public String mimeType;
        public long size;
    }

    @Data
    public static class DeleteFileRequest {
        public String fileId;
    }
}