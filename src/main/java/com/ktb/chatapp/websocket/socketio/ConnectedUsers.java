package com.ktb.chatapp.websocket.socketio;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ConnectedUsers {

    private static final String USER_SOCKET_KEY_PREFIX = "conn_users:userid:";

    @Qualifier("websocketRedisTemplate")
    private final RedisTemplate<String, Object> redis;

    /**
     * Get connected user info
     */
    public SocketUser get(String userId) {
        Object value = redis.opsForValue().get(buildKey(userId));
        if (value == null) return null;
        return (SocketUser) value;
    }

    /**
     * Set or update connected user info
     */
    public void set(String userId, SocketUser socketUser) {
        redis.opsForValue().set(buildKey(userId), socketUser);
    }

    /**
     * Delete connected user info
     */
    public void del(String userId) {
        redis.delete(buildKey(userId));
    }

    /**
     * Count how many connected user keys exists
     * (Redis doesn't have direct count, so we count matching keys)
     */
    public int size() {
        Set<String> keys = redis.keys(USER_SOCKET_KEY_PREFIX + "*");
        return (keys == null) ? 0 : keys.size();
    }

    private String buildKey(String userId) {
        return USER_SOCKET_KEY_PREFIX + userId;
    }
}