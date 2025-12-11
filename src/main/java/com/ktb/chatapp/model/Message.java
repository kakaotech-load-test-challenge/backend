package com.ktb.chatapp.model;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
@CompoundIndexes({
        @CompoundIndex(name = "readers_userId_idx", def = "{'readers.userId': 1}"),
        @CompoundIndex(name = "room_isDeleted_timestamp_idx", def = "{'room': 1, 'isDeleted': 1, 'timestamp': -1}")
})
public class Message {

    @Id
    private String id;

    @Indexed
    @Field("room")
    private String roomId;

    @Size(max = 10000, message = "메시지는 10000자를 초과할 수 없습니다.")
    private String content;

    @Field("sender")
    private String senderId;

    private MessageType type;

    @Field("file")
    private String fileId;  // File 문서의 id

    private AiType aiType;

    @Builder.Default
    private List<String> mentions = new ArrayList<>();

    @CreatedDate
    private LocalDateTime timestamp;

    @Builder.Default
    private Map<String, Set<String>> reactions = new HashMap<>();

    @Builder.Default
    private List<MessageReader> readers = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Indexed
    @Builder.Default
    private Boolean isDeleted = false;

    public long toTimestampMillis() {
        return timestamp.atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    /** 리액션 추가 */
    public boolean addReaction(String reaction, String userId) {
        if (this.reactions == null) this.reactions = new HashMap<>();
        Set<String> users = this.reactions.computeIfAbsent(reaction, k -> new HashSet<>());
        return users.add(userId);
    }

    /** 리액션 제거 */
    public boolean removeReaction(String reaction, String userId) {
        if (this.reactions == null) return false;

        Set<String> users = this.reactions.get(reaction);
        if (users != null && users.remove(userId)) {
            if (users.isEmpty()) {
                this.reactions.remove(reaction);
            }
            return true;
        }
        return false;
    }

    /**
     * 파일 메타데이터를 메시지에 저장
     * (프리사인 URL 기반)
     */
    public void attachFileMetadata(File file) {
        if (this.fileId != null && this.metadata == null) {
            this.metadata = new HashMap<>();

            this.metadata.put("fileType", file.getMimeType());
            this.metadata.put("fileSize", file.getSize());
            this.metadata.put("originalName", file.getOriginalName());
            this.metadata.put("fileUrl", file.getUrl());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageReader {
        private String userId;
        private LocalDateTime readAt;
    }
}