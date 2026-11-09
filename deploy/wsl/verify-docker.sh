#!/bin/bash
# 容器运行时可用性验证（ADR-0005 第 4 节阻塞项关闭证据）。
set -u
echo "=== ENV ==="
echo "distro=$(grep PRETTY_NAME /etc/os-release | cut -d= -f2- | tr -d '"')"
echo "kernel=$(uname -r)"
GW=$(ip route | awk '/^default/ {print $3; exit}')
echo "wsl_default_gateway=$GW"
echo "host_proxy=http://$GW:${DOCKER_PROXY_PORT:-65532}"

echo
echo "=== ENGINE ==="
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
docker compose version
docker info --format 'driver={{.Driver}} cgroup={{.CgroupVersion}} containerd_snapshotter={{.DriverStatus}}'
docker info --format 'mirrors={{range .RegistryConfig.Mirrors}}{{.}} {{end}}'
echo "daemon.json:"; cat /etc/docker/daemon.json

echo
echo "=== IMAGES ==="
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}'

echo
echo "=== RUN: hello-world ==="
docker run --rm hello-world | grep -E 'Hello from Docker|appears to be working'

echo
echo "=== RUN: postgres:16 ==="
docker run --rm postgres:16 postgres --version
docker run -d --rm --name pgverify -e POSTGRES_PASSWORD=devonly -p 55432:5432 postgres:16 >/dev/null
sleep 6
docker exec pgverify pg_isready -U postgres
docker exec pgverify psql -U postgres -tAc 'select version();'
docker rm -f pgverify >/dev/null
echo "container_cleaned=yes"
echo "leftover_containers=$(docker ps -aq | wc -l)"
