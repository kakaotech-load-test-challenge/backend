package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResponse {

    private String url;

    private String originalName;

    private String mimeType;

    private long size;

    /**
     * metadata → FileResponse 변환
     * (Message.metadata 구조 기반)
     */
    public static FileResponse fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return null;

        return FileResponse.builder()
                .url((String) metadata.get("fileUrl"))
                .originalName((String) metadata.get("originalName"))
                .mimeType((String) metadata.get("mimeType"))
                .size(metadata.get("size") instanceof Number
                        ? ((Number) metadata.get("size")).longValue()
                        : 0L)
                .build();
    }
}