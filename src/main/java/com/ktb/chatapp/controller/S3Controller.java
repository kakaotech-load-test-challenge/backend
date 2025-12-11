package com.ktb.chatapp.controller;

import com.ktb.chatapp.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/s3")
@RequiredArgsConstructor
public class S3Controller {

    private final S3Service s3Service;

    @GetMapping("/presign")
    public ResponseEntity<String> getPresignUrl(@RequestParam String fileName) {
        String url = s3Service.generatePresignedUrl(fileName);
        return ResponseEntity.ok(url);
    }
}