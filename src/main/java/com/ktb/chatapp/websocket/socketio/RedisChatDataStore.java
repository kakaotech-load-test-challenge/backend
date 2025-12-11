package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PREFIX = "chat:store:";

    private String fullKey(String key) {
        return PREFIX + key;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(fullKey(key));
            String json = bucket.get();
            if (json == null) return Optional.empty();

            T value = objectMapper.readValue(json, type);
            return Optional.of(value);

        } catch (Exception e) {
            log.error("RedisChatDataStore.get() error: key={}, error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(fullKey(key));
            String json = objectMapper.writeValueAsString(value);
            bucket.set(json);
        } catch (JsonProcessingException e) {
            log.error("RedisChatDataStore.set() JSON serialization error: value={}", value, e);
        }
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(fullKey(key)).delete();
    }

    @Override
    public int size() {
        RKeys keys = redissonClient.getKeys();
        return Math.toIntExact(keys.countExists(PREFIX + "*"));
    }
}