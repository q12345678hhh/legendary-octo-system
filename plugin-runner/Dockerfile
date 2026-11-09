# 隔离插件执行器镜像。
# 安全约束（见 docs/spikes/spike-D-plugin-isolation.md 第 7 节）：
#   - PID 1 必须是监督进程 runner.py，插件只能作为子进程运行
#   - 不使用 shell 形式的 ENTRYPOINT，避免信号被 shell 吞掉
#   - 以非 root 用户运行；网络与内存限制由编排层传入
FROM python:3.12-slim

LABEL org.opencontainers.image.title="platform-plugin-runner" \
      org.opencontainers.image.description="隔离插件执行器：监督进程作为 PID 1，退出码按信号语义透传"

RUN adduser --disabled-password --gecos "" --uid 10001 plugin

WORKDIR /app
COPY runner.py /app/runner.py

ENV PLUGIN_MEMORY_MB=256 \
    PLUGIN_CPU_SECONDS=60 \
    PLUGIN_OPEN_FILES=256 \
    PLUGIN_PROCESSES=32 \
    PYTHONDONTWRITEBYTECODE=1

USER plugin

ENTRYPOINT ["python3", "/app/runner.py"]
