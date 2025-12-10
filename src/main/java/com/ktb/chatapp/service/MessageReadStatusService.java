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
            Query query = new Query(
                    Criteria.where("_id").in(messageIds)
                            .and("readers.userId").ne(userId)
            );

            Update update = new Update()
                    .addToSet("readers",
                            new Document("userId", userId)
                                    .append("readAt", LocalDateTime.now())
                    );

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