package com.ktb.chatapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;

    public void updateReadStatus(List<String> messageIds, String userId) {

        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        try {
            // 아직 읽지 않은 메시지만 업데이트
            Query query = new Query(
                    Criteria.where("_id").in(messageIds)
                            .and("readers.userId").ne(userId)
            );

            // addToSet + each → 중복 없이 한번에 삽입
            Document readerInfo = new Document("userId", userId)
                    .append("readAt", LocalDateTime.now());

            Update update = new Update()
                    .addToSet("readers")
                    .each(readerInfo);

            var result = mongoTemplate.updateMulti(query, update, "messages");

            log.debug(
                    "ReadStatus updated => matched={}, modified={}, userId={}",
                    result.getMatchedCount(),
                    result.getModifiedCount(),
                    userId
            );

        } catch (Exception e) {
            log.error("Error updating read status for user {}", userId, e);
        }
    }
}