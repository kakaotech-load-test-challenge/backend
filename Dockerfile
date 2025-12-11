# syntax=docker/dockerfile:1.7

FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /workspace

# 캐시 최적화 - 먼저 pom.xml만 복사
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 소스 복사
COPY src ./src

# 테스트 패스/트랜잭션 문제 방지를 위해 테스트 스킵
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 빌드 결과물 복사
COPY --from=builder /workspace/target/*.jar app.jar

# 포트 (API + WebSocket)
EXPOSE 5001 5002

# JVM 메모리 최적화 (t3.small 최적값)
ENV JAVA_OPTS="-Xms512m -Xmx1024m"

# 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]