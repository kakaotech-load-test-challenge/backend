package com.ktb.chatapp.model;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "files")
public class File {

    @Id
    private String id;

    // 업로더 ID
    @Indexed
    private String userId;

    // S3 파일 접근 URL
    private String url;

    // 원본 파일명
    private String originalName;

    // MIME 타입
    private String mimeType;

    // 파일 크기
    private long size;

    @CreatedDate
    @Indexed
    private LocalDateTime uploadDate;
}