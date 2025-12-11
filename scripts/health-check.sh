#!/bin/bash

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Running health checks${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 환경 변수 확인
if [ -z "$INSTANCE_IPS" ]; then
    echo -e "${RED}Error: INSTANCE_IPS environment variable is not set${NC}"
    exit 1
fi

# 헬스체크 함수
health_check() {
    local IP=$1
    local HOST_ALIAS="ec2-${IP//./-}"
    local MAX_RETRIES=30
    local RETRY_INTERVAL=10
    local ALL_HEALTHY=true

    echo -e "${YELLOW}Health checking instance: $IP${NC}"

    # Docker 컨테이너 상태 확인
    echo "  Checking container status..."
    CONTAINER_STATUS=$(ssh "$HOST_ALIAS" "cd ~/app && docker-compose -f docker-compose.yaml -f docker-compose.prod.yml ps --format json" | jq -r '.[] | "\(.Service): \(.State)"')

    if [ -n "$CONTAINER_STATUS" ]; then
        echo "$CONTAINER_STATUS" | while read -r line; do
            echo "    $line"
            if [[ ! "$line" =~ "running" ]]; then
                ALL_HEALTHY=false
            fi
        done
    else
        echo -e "${RED}    Failed to get container status${NC}"
        ALL_HEALTHY=false
    fi

    # Backend 헬스체크 (포트 5001 가정)
    echo "  Checking backend health..."
    for i in $(seq 1 $MAX_RETRIES); do
        if ssh "$HOST_ALIAS" "curl -sf http://localhost:5001/actuator/health > /dev/null 2>&1"; then
            echo -e "    ${GREEN}Backend is healthy${NC}"
            break
        else
            if [ $i -eq $MAX_RETRIES ]; then
                echo -e "    ${RED}Backend health check failed after $MAX_RETRIES retries${NC}"
                ALL_HEALTHY=false
            else
                echo "    Waiting for backend... (attempt $i/$MAX_RETRIES)"
                sleep $RETRY_INTERVAL
            fi
        fi
    done

    # Frontend 헬스체크 (포트 3000 가정)
    echo "  Checking frontend health..."
    for i in $(seq 1 $MAX_RETRIES); do
        if ssh "$HOST_ALIAS" "curl -sf http://localhost:3000 > /dev/null 2>&1"; then
            echo -e "    ${GREEN}Frontend is healthy${NC}"
            break
        else
            if [ $i -eq $MAX_RETRIES ]; then
                echo -e "    ${RED}Frontend health check failed after $MAX_RETRIES retries${NC}"
                ALL_HEALTHY=false
            else
                echo "    Waiting for frontend... (attempt $i/$MAX_RETRIES)"
                sleep $RETRY_INTERVAL
            fi
        fi
    done

    if [ "$ALL_HEALTHY" = true ]; then
        echo -e "${GREEN}  All health checks passed for $IP${NC}"
    else
        echo -e "${RED}  Some health checks failed for $IP${NC}"
        return 1
    fi

    echo ""
}

# 순차 헬스체크 (실패 시 즉시 중단하려면)
FAILED=0
for IP in $INSTANCE_IPS; do
    if ! health_check "$IP"; then
        FAILED=$((FAILED + 1))
    fi
done

echo -e "${GREEN}========================================${NC}"
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All health checks passed${NC}"
else
    echo -e "${RED}$FAILED instance(s) failed health checks${NC}"
    exit 1
fi
echo -e "${GREEN}========================================${NC}"