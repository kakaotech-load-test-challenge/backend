package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${redis.websocket.host:localhost}")
    private String redisHost;

    @Value("${redis.websocket.port:6379}")
    private Integer redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);

        return Redisson.create(config);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(
            AuthTokenListener authTokenListener,
            RedissonClient redissonClient
    ) {

        com.corundumstudio.socketio.Configuration config =
                new com.corundumstudio.socketio.Configuration();

        config.setHostname(host);
        config.setPort(port);

        // TCP 소켓 성능 설정
        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(true);
        socketConfig.setAcceptBackLog(100);
        config.setSocketConfig(socketConfig);

        // WebSocket 기본 설정
        config.setOrigin("*");
        config.setPingInterval(25000);
        config.setPingTimeout(60000);
        config.setUpgradeTimeout(10000);

        config.setBossThreads(1);
        config.setWorkerThreads(2);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        config.setStoreFactory(new RedissonStoreFactory(redissonClient));

        log.info("Socket.IO server running on {}:{} (Redis clustering enabled)", host, port);

        SocketIOServer server = new SocketIOServer(config);

        // JWT 인증 필터 적용
        server.getNamespace(Namespace.DEFAULT_NAME)
                .addAuthTokenListener(authTokenListener);

        return server;
    }

    // 이벤트 리스너 자동 등록
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(
            @Lazy SocketIOServer server
    ) {
        return new SpringAnnotationScanner(server);
    }

    @Bean
    public ChatDataStore chatDataStore(RedissonClient redissonClient) {
        return new RedisChatDataStore(redissonClient);
    }
}