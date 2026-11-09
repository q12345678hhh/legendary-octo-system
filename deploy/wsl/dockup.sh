#!/bin/sh
# 在 WSL2 的 Ubuntu 内启动 Docker Engine（无 systemd 环境）。
# 1) 宿主侧存在本地代理（默认 127.0.0.1:65532），WSL 访问不到 loopback，
#    因此走默认网关 IP 访问宿主代理写入 daemon 配置并注入进程环境。
# 2) Docker Hub 的 blob CDN 经代理频繁 reset，故配置 registry mirror。
set -u

PROXY_PORT=${DOCKER_PROXY_PORT:-65532}
MIRROR=${DOCKER_MIRROR:-https://docker.m.daocloud.io}
WHAT=${1:-start}
CONF=/etc/docker/daemon.json
LOG=/var/log/dockerd.log

GW=$(ip route | awk '/^default/ {print $3; exit}')
PROXY="http://$GW:$PROXY_PORT"

cat >"$CONF" <<EOF
{
  "registry-mirrors": ["$MIRROR"],
  "proxies": {
    "http-proxy": "$PROXY",
    "https-proxy": "$PROXY",
    "no-proxy": "localhost,127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8"
  }
}
EOF
echo "docker: conf written mirror=$MIRROR proxy=$PROXY"

if [ "$WHAT" = "conf" ]; then
    exit 0
fi

export HTTP_PROXY="$PROXY"
export HTTPS_PROXY="$PROXY"
export NO_PROXY="localhost,127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8"

if docker info >/dev/null 2>&1; then
    running_mirror=$(docker info --format '{{range .RegistryConfig.Mirrors}}{{.}} {{end}}' 2>/dev/null)
    echo "docker: already running ($(docker info --format '{{.ServerVersion}}')) mirrors=$running_mirror"
    case "$running_mirror" in
        *daocloud*) exit 0;;
    esac
    echo "docker: restarting to apply new daemon config"
    pkill -x dockerd 2>/dev/null
    i=0
    while pgrep -x dockerd >/dev/null 2>&1 && [ $i -lt 15 ]; do i=$((i + 1)); sleep 1; done
fi

proxy_ok=no
if [ -n "$GW" ] && curl -s -o /dev/null --max-time 5 -x "$PROXY" https://registry-1.docker.io/v2/; then
    proxy_ok=yes
fi
echo "docker: gateway=$GW proxy_reachable=$proxy_ok"
if [ "$proxy_ok" = no ]; then
    echo "docker: warning: host proxy unreachable, pulls will likely fail" >&2
fi

nohup dockerd >>"$LOG" 2>&1 &
i=0
while [ $i -lt 30 ]; do
    if docker info >/dev/null 2>&1; then
        echo "docker: started ($(docker info --format '{{.ServerVersion}}')) driver=$(docker info --format '{{.Driver}}') cgroup=$(docker info --format '{{.CgroupVersion}}')"
        exit 0
    fi
    i=$((i + 1)); sleep 1
done
echo "docker: failed to start, see $LOG" >&2
tail -n 20 "$LOG" >&2
exit 1
