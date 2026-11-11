# API 运行镜像（WP01-02 容器化验收）。
#
# 只搬运已构建的可执行 jar，不在镜像里跑 Maven：本地、CI 与容器用同一份产物，
# 容器构建也无需再下载一遍依赖。前置条件是先有一次构建：
#   mvn -B -pl boot-api -am package -DskipTests
# scripts/dev-up.ps1 -App 会在缺 jar 时自动补这一步。
# 构建上下文就是本目录，见 .dockerignore。
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="platform-api" \
      org.opencontainers.image.description="API 进程：请求上下文、租户绑定、健康端点与 outbox 写入"

# 非 root 运行，UID 固定便于日志与卷归属
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin platform

WORKDIR /app
COPY --chown=platform:platform target/*.jar /app/app.jar

# 只用 JAVA_TOOL_OPTIONS 调参，不引入会吞信号的 shell 启动脚本
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER platform
EXPOSE 8080

# 端口级存活探针：镜像内没有 curl，不装额外包；就绪判定由 dev-up.ps1 -App
# 从宿主机请求 /actuator/health 完成。
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'

# exec 形式让 java 成为 PID 1，容器停止时能收到 SIGTERM 走 graceful shutdown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
