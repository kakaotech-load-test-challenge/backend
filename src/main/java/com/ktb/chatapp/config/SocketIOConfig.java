package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import lombok.extern.slf4j.Slf4j;
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

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener) {

        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);

        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(true);      // 지연 최소화
        socketConfig.setAcceptBackLog(100);    // 연결 대기열 확장 → 부하테스트 안정성 크게 증가
        config.setSocketConfig(socketConfig);

        // WebSocket Origin
        config.setOrigin("*"); // 필요하면 특정 도메인으로 제한 가능

        config.setPingInterval(25000); // 25초마다 ping
        config.setPingTimeout(60000);  // pong 없으면 60초 후 연결 종료
        config.setUpgradeTimeout(10000);

        config.setBossThreads(1);   // accept 전담 스레드
        config.setWorkerThreads(2); // 메시지 처리 스레드 → CPU 폭발 방지

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(new MemoryStoreFactory()); // 단일 노드 환경

        // Logging
        log.info("Socket.IO configured on {}:{} (boss={}, worker={})",
                host, port, config.getBossThreads(), config.getWorkerThreads());

        // 서버 생성
        SocketIOServer server = new SocketIOServer(config);

        // 인증 listener 등록
        server.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);

        return server;
    }

    /**
     * Event listener 등록
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer server) {
        return new SpringAnnotationScanner(server);
    }

    /**
     * 단일 노드 환경에서 사용하는 In-memory 데이터 저장소
     */
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore() {
        return new LocalChatDataStore();
    }
}