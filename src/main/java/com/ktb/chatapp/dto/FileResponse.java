package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ktb.chatapp.model.File;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResponse {

    @JsonProperty("_id")
    private String id;

    private String url;

    private String originalName;

    private String mimeType;

    private long size;

    private String userId;

    private LocalDateTime uploadDate;

    /**
     * File 엔티티 → FileResponse 변환
     */
    public static FileResponse from(File file) {
        return FileResponse.builder()
                .id(file.getId())
                .url(file.getUrl())
                .originalName(file.getOriginalName())
                .mimeType(file.getMimeType())
                .size(file.getSize())
                .userId(file.getUserId())
                .uploadDate(file.getUploadDate())
                .build();
    }

    public static FileResponse fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return null;

        return FileResponse.builder()
                .url((String) metadata.get("fileUrl"))
                .originalName((String) metadata.get("originalName"))
                .mimeType((String) metadata.get("fileType"))
                .size(metadata.get("fileSize") instanceof Number
                        ? ((Number) metadata.get("fileSize")).longValue()
                        : 0L)
                .build();
    }
}