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

        // 읽음 처리(readers.userId) 최적화
        @CompoundIndex(
                name = "readers_userId_idx",
                def = "{'readers.userId': 1}"
        ),

        // 메시지 조회(room + timestamp DESC) 인덱스
        // 방 입장 시 최근 메시지 50개 불러오는 쿼리가 반드시 이 인덱스를 사용함
        @CompoundIndex(
                name = "room_timestamp_idx",
                def = "{'room': 1, 'timestamp': -1}"
        ),

        // 삭제된 메시지 필터링 시 최적화
        @CompoundIndex(
                name = "room_isDeleted_timestamp_idx",
                def = "{'room': 1, 'isDeleted': 1, 'timestamp': -1}"
        ),

        // 특정 유저 메시지 조회 (관리자 기능·검색 기능에서 유용)
        @CompoundIndex(
                name = "sender_timestamp_idx",
                def = "{'sender': 1, 'timestamp': -1}"
        )
})
public class Message {

    @Id
    private String id;

    // roomId → 실제 MongoDB 필드는 "room"
    @Indexed
    @Field("room")
    private String roomId;

    @Size(max = 10000, message = "메시지는 10000자를 초과할 수 없습니다.")
    private String content;

    @Indexed  // 유저 메시지 조회 속도 향상
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

    // reactionType → Set<UserId> 형태
    @Builder.Default
    private Map<String, Set<String>> reactions = new HashMap<>();

    // 읽음 상태 리스트
    @Builder.Default
    private List<MessageReader> readers = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    // 단일 인덱스 제거 (복합 인덱스에서 대체됨)
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
     * 파일 메타데이터 저장 (S3 프리사인 기반)
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