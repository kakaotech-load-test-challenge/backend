package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {

    Page<Message> findByRoomIdAndIsDeletedAndTimestampBefore(
            String roomId,
            Boolean isDeleted,
            LocalDateTime timestamp,
            Pageable pageable
    );

    /**
     * 특정 시간 이후의 메시지 수 카운트 (삭제되지 않은 메시지만)
     */
    @Query(value = "{ 'room': ?0, 'isDeleted': false, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);
}