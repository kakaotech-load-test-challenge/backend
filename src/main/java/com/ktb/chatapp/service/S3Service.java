package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.PresignedUrlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${cloud.aws.region}")
    private String region;

    public PresignedUrlResponse generatePresignedUrl(String fileName, String contentType) {

        String key = "rooms/" + UUID.randomUUID() + "_" + fileName;

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(3))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedObject =
                s3Presigner.presignPutObject(presignRequest);

        String presignedUrl = presignedObject.url().toString();
        String fileUrl = String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);

        return new PresignedUrlResponse(presignedUrl, fileUrl);
    }
}