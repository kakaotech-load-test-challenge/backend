package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.PresignedUrlResponse;
import com.ktb.chatapp.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/s3")
@RequiredArgsConstructor
public class S3Controller {

    private final S3Service s3Service;

    @GetMapping("/presign")
    public PresignedUrlResponse getPresignedUrl(
            @RequestParam String fileName,
            @RequestParam String contentType
    ) {
        return s3Service.generatePresignedUrl(fileName, contentType);
    }
}