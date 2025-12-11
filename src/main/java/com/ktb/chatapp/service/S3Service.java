package com.ktb.chatapp.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URL;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${app.s3.region}")
    private String region;

    @Value("${app.s3.access-key}")
    private String accessKey;

    @Value("${app.s3.secret-key}")
    private String secretKey;

    private S3Presigner presigner;

    @PostConstruct
    public void init() {
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException("AWS credentials are missing.");
        }

        this.presigner = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .build();

        log.info("S3Presigner initialized for bucket {}", bucket);
    }

    public String generatePresignedUrl(String originalFileName) {

        // 파일명 sanitize (공백·괄호·한글 등 제거)
        String sanitized = originalFileName
                .replaceAll("\\s+", "_")         // 공백 → _
                .replaceAll("[()]", "")          // 괄호 제거
                .replaceAll("[^a-zA-Z0-9._-]", ""); // 안전 문자만 허용

        // UUID 붙여서 충돌 방지
        String key = "uploads/" + UUID.randomUUID() + "_" + sanitized;

        // Content-Type 제거 → 프론트가 보낸 헤더와 mismatch 방지
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        // Presigned PUT URL 생성 (10분 유효)
        PresignedPutObjectRequest presigned = presigner.presignPutObject(b -> b
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(objectRequest)
        );

        URL url = presigned.url();

        log.info("Generated Presigned PUT URL for file {} → {}", key, url);

        return url.toString();
    }
}