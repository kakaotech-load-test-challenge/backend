package com.ktb.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "chatTaskExecutor")
    public Executor chatTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 코어 스레드 수 (기본 동시 작업 수)
        executor.setCorePoolSize(8);

        // 최대 스레드 수 (피크 시 상한선)
        executor.setMaxPoolSize(16);

        // 큐 용량 (대기 작업 수)
        executor.setQueueCapacity(1000);

        // 스레드 이름 prefix (디버깅에 도움)
        executor.setThreadNamePrefix("chat-async-");

        executor.initialize();
        return executor;
    }
}