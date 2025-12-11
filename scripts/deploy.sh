#!/bin/bash

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Starting deployment to EC2 instances${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 환경 변수 확인
if [ -z "$INSTANCE_IPS" ]; then
    echo -e "${RED}Error: INSTANCE_IPS environment variable is not set${NC}"
    exit 1
fi

# 배포 함수
deploy_to_instance() {
    local IP=$1
    local HOST_ALIAS="ec2-${IP//./-}"

    echo -e "${YELLOW}Deploying to instance: $IP${NC}"

    # 배포 디렉토리 생성
    ssh "$HOST_ALIAS" "mkdir -p ~/app"

    # Docker Compose 파일 전송
    echo "  Copying configuration files..."
    scp ./docker-compose.yaml "$HOST_ALIAS:~/app/"
    scp ./docker-compose.prod.yml "$HOST_ALIAS:~/app/"

    # 환경 변수 파일 생성
    ssh "$HOST_ALIAS" "cat > ~/app/.env <<EOF
BACKEND_IMAGE=$BACKEND_IMAGE
EOF"

    # Docker Compose로 서비스 배포
    echo "  Pulling latest images..."
    ssh "$HOST_ALIAS" "cd ~/app && docker-compose -f docker-compose.yaml -f docker-compose.prod.yml pull"

    echo "  Stopping existing containers..."
    ssh "$HOST_ALIAS" "cd ~/app && docker-compose -f docker-compose.yaml -f docker-compose.prod.yml down --remove-orphans"

    echo "  Starting containers..."
    ssh "$HOST_ALIAS" "cd ~/app && docker-compose -f docker-compose.yaml -f docker-compose.prod.yml up -d"

    # 정리
    echo "  Cleaning up old images..."
    ssh "$HOST_ALIAS" "docker image prune -af --filter 'until=24h' || true"

    echo -e "${GREEN}  Deployment completed for $IP${NC}"
    echo ""
}

# 병렬 배포 (최대 5개 동시)
PARALLEL_JOBS=5
export -f deploy_to_instance
export BACKEND_IMAGE
export RED GREEN YELLOW NC

echo "$INSTANCE_IPS" | xargs -n 1 -P $PARALLEL_JOBS -I {} bash -c 'deploy_to_instance "$@"' _ {}

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment completed for all instances${NC}"
echo -e "${GREEN}========================================${NC}"