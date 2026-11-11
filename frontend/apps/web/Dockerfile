# Web 运行镜像（WP01-02 容器化验收）。
#
# 静态产物来自本机或 CI 的同一条命令：pnpm --filter @platform/web build。
# 不在镜像里跑 pnpm install/build：避免容器构建时再拉一遍依赖，
# 也让“调试用的构建”和“验收用的构建”是同一份产物。
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="platform-web" \
      org.opencontainers.image.description="管理端静态资源与 /api 反向代理"

# 默认配置会把未知路径 404，SPA 需要回落到 index.html，且 /api 要转到 api 容器
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

EXPOSE 80

# alpine 自带 busybox wget，不额外装包
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
