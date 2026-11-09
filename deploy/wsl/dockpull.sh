#!/bin/bash
# 带重试的 docker pull：宿主代理偶发 TLS reset，需多次尝试。
set -u
IMAGE=${1:-hello-world:latest}
TRIES=${2:-8}
i=1
while [ "$i" -le "$TRIES" ]; do
    echo "--- pull attempt $i/$TRIES: $IMAGE ---"
    if docker pull "$IMAGE"; then
        echo "pull_ok attempt=$i"
        docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | head -n 5
        exit 0
    fi
    i=$((i + 1))
    sleep 2
done
echo "pull_failed after $TRIES attempts: $IMAGE" >&2
exit 1
