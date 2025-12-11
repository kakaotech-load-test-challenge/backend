package com.ktb.chatapp.dto;

import lombok.Data;

@Data
public class FileMessagePayload {
    private String roomId;
    private String url;
    private String mimeType;
    private long size;
}
