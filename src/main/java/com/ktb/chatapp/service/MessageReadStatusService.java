package com.ktb.chatapp.service;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;
    private final RedisTemplate<String, Object> redis;

    private static final String READ_KEY_PREFIX = "readstatus:message:";
    private static final String PENDING_KEY = "readstatus:pending";

    public MessageReadStatusService(
            MongoTemplate mongoTemplate,
            @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redis
    ) {
        this.mongoTemplate = mongoTemplate;
        this.redis = redis;
    }

    /**
     * 클라이언트에서 메시지를 읽으면 Redis에만 기록해둔다.
     * MongoDB는 즉시 업데이트하지 않고 배치로 처리한다.
     */
    public void updateReadStatus(List<String> messageIds, String userId) {

        if (messageIds == null || messageIds.isEmpty()) return;

        try {
            for (String messageId : messageIds) {

                String key = READ_KEY_PREFIX + messageId;

                // Redis Set에 사용자 읽음 기록 추가
                redis.opsForSet().add(key, userId);

                // 이 메시지가 flush 대상임을 표시
                redis.opsForSet().add(PENDING_KEY, messageId);
            }

        } catch (Exception e) {
            log.error("Redis logging for read-status failed", e);
        }
    }

    /**
     * 5초마다 실행하여 Redis에 기록된 읽음 정보를 MongoDB에 일괄 반영한다.
     */
    @Scheduled(fixedDelay = 5000)
    public void flushPendingStatuses() {
        try {
            Set<Object> pending = redis.opsForSet().members(PENDING_KEY);

            if (pending == null || pending.isEmpty()) return;

            for (Object midObj : pending) {

                String messageId = midObj.toString();
                String readKey = READ_KEY_PREFIX + messageId;

                // Redis Set에서 메시지를 읽은 userId 목록 가져오기
                Set<Object> userIds = redis.opsForSet().members(readKey);
                if (userIds == null || userIds.isEmpty()) continue;

                List<String> users = userIds.stream()
                        .map(Object::toString)
                        .toList();

                Query query = new Query(
                        Criteria.where("_id").is(messageId)
                );

                // readers 배열에 추가할 Document 목록
                List<Document> readerDocs = users.stream()
                        .map(uid -> new Document("userId", uid)
                                .append("readAt", LocalDateTime.now()))
                        .collect(Collectors.toList());

                // MongoDB update (배열에 addToSet)
                Update update = new Update().addToSet("readers").each(readerDocs);

                mongoTemplate.updateMulti(query, update, "messages");

                // Redis 정리
                redis.delete(readKey);
                redis.opsForSet().remove(PENDING_KEY, messageId);
            }

        } catch (Exception e) {
            log.error("Error during read-status MongoDB flush", e);
        }
    }
}