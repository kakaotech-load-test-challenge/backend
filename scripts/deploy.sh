#!/bin/bash

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Starting Deployment ===${NC}"

# 필수 변수 체크
if [ -z "$INSTANCE_IPS" ] || [ -z "$EC2_USER" ] || [ -z "$IMAGE_TAG" ]; then
    echo "Error: Required environment variables are missing."
    exit 1
fi

SSH_OPTS="-i ~/.ssh/deploy_key -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

deploy_to_instance() {
    local IP=$1
    echo -e "${YELLOW}Deploying to $IP ...${NC}"

    # 1. 작업 디렉토리 생성
    ssh $SSH_OPTS "$EC2_USER@$IP" "mkdir -p ~/ktb"

    # 2. docker-compose.yml 전송 (덮어쓰기)
    # 로컬에 있는 파일을 서버로 전송합니다.
    scp $SSH_OPTS ./docker-compose.prod.yml "$EC2_USER@$IP:~/ktb/docker-compose.prod.yml"

    # 3. .env 파일 생성 (이미지 태그 및 Redis 설정 지정)
    # docker-compose.yml이 ${IMAGE_TAG:-latest}를 읽는데,
    # 여기서 IMAGE_TAG=커밋해시 값을 주입하여 방금 빌드한 버전을 띄우게 합니다.
    # Redis 호스트 정보도 함께 전달합니다.
    ssh $SSH_OPTS "$EC2_USER@$IP" "cat > ~/ktb/.env.prod << EOF
IMAGE_TAG=$IMAGE_TAG
REDISA_HOST=10.0.101.47
REDISB_HOST=10.0.101.120
REDIS_PORT=6379
EOF
"

    # 4. 배포 실행
    # --pull always: 이미지를 확실하게 새로 받음
    ssh $SSH_OPTS "$EC2_USER@$IP" "cd ~/ktb && docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --pull always"

    # 5. 미사용 이미지 정리 (선택사항)
    ssh $SSH_OPTS "$EC2_USER@$IP" "docker image prune -f"

    echo -e "${GREEN}Done: $IP${NC}"
}

# IP 목록을 순회하며 배포
for IP in $INSTANCE_IPS; do
    deploy_to_instance "$IP" &
done

wait
echo -e "${GREEN}=== All Deployments Completed ===${NC}"
