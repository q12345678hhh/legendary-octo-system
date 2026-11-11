# Worker 运行镜像（WP01-02 容器化验收）。
# 与 API 同源构建：先 mvn -B -pl boot-worker -am package -DskipTests，再交给本文件。
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="platform-worker" \
      org.opencontainers.image.description="异步 Worker：认领 outbox、派发领域事件"

RUN useradd --uid 10002 --create-home --shell /usr/sbin/nologin platform

WORKDIR /app
COPY --chown=platform:platform target/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER platform

# Worker 没有 Web 端口，健康与否看进程与日志（OutboxDispatcher 每秒心跳见 runbook）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
