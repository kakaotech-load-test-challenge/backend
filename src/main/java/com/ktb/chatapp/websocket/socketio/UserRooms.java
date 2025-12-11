package com.ktb.chatapp.websocket.socketio;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class UserRooms {

    private static final String USER_ROOM_KEY_PREFIX = "userroom:roomids:";

    private final RedisTemplate<String, Object> redis;

    public UserRooms(
            @Qualifier("websocketRedisTemplate") RedisTemplate<String, Object> redis
    ) {
        this.redis = redis;
    }

    /**
     * Get all room IDs the user is currently in.
     */
    @SuppressWarnings("unchecked")
    public Set<String> get(String userId) {
        String key = buildKey(userId);

        Set<Object> values = redis.opsForSet().members(key);
        if (values == null) {
            return Collections.emptySet();
        }

        return values.stream()
                .map(obj -> (String) obj)
                .collect(Collectors.toSet());
    }

    /**
     * Add a room ID for a user
     */
    public void add(String userId, String roomId) {
        redis.opsForSet().add(buildKey(userId), roomId);
    }

    /**
     * Remove a specific room ID for a user
     */
    public void remove(String userId, String roomId) {
        redis.opsForSet().remove(buildKey(userId), roomId);
    }

    /**
     * Remove all room associations for a user
     */
    public void clear(String userId) {
        redis.delete(buildKey(userId));
    }

    /**
     * Check if user is in a specific room
     */
    public boolean isInRoom(String userId, String roomId) {
        return redis.opsForSet().isMember(buildKey(userId), roomId);
    }

    private String buildKey(String userId) {
        return USER_ROOM_KEY_PREFIX + userId;
    }
}