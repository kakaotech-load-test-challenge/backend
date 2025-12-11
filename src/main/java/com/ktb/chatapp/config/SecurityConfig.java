package com.ktb.chatapp.config;

import com.ktb.chatapp.security.CustomBearerTokenResolver;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtDecoder jwtDecoder;
    private final CustomBearerTokenResolver bearerTokenResolver;
    private final SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    private static final List<String> CORS_ALLOWED_ORIGINS = List.of("*");

    private static final List<String> CORS_ALLOWED_HEADERS = List.of(
            "Content-Type",
            "Authorization",
            "x-auth-token",
            "x-session-id",
            "Cache-Control",
            "Pragma"
    );

    private static final List<String> CORS_EXPOSED_HEADERS = List.of(
            "x-auth-token",
            "x-session-id"
    );

    private static final List<String> CORS_ALLOWED_METHODS = List.of(
            "GET", "POST", "PUT", "DELETE", "OPTIONS"
    );

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration
    ) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(request -> createCorsConfiguration()))

                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/**",
                                "/api/v3/api-docs/**",
                                "/api/swagger-ui/**",
                                "/api/swagger-ui.html",
                                "/api/docs/**",
                                "/api/s3/presign"     // presign 허용 or 세션검증 전 진입 가능
                        ).permitAll()

                        .requestMatchers("/api/s3/**").authenticated()  // 그 외 S3 API는 인증
                        .requestMatchers("/api/**").authenticated()      // 기본 API 인증
                        .anyRequest().permitAll()
                )

                // 모든 /api/** 요청에 대해 Security Filter 작동
                .securityMatcher("/api/**")

                // 세션을 사용하지 않음
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // OAuth2 Resource Server 설정 (JWT)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                );

        return http.build();
    }

    private CorsConfiguration createCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();

        if (CORS_ALLOWED_ORIGINS.contains("*")) {
            log.warn("CORS WARNING: '*' is allowed for all origins. Adjust for production.");
        }

        config.setAllowedOriginPatterns(CORS_ALLOWED_ORIGINS);
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        config.setExposedHeaders(CORS_EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1).getSeconds());

        return config;
    }
}