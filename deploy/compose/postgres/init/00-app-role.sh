#!/bin/sh
# 创建运行期应用账号（WP01-02）。
#
# 由 postgres 镜像在空数据卷首次启动时执行一次；已有数据卷不会重跑，
# 需要重建角色时用 `docker compose down -v` 清卷后重来。
#
# 关键约束：NOBYPASSRLS + 非表所有者，RLS 策略才对运行期连接真实生效。
# 反过来，迁移用 platform_owner，它是表所有者，天然绕过 RLS——这是分工，不是漏洞。
set -eu

: "${PLATFORM_APP_PASSWORD:?PLATFORM_APP_PASSWORD 未设置}"

# 用 psql 变量而不是字符串拼接，口令里有引号或反斜杠也不会破坏语句。
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v app_pw="$PLATFORM_APP_PASSWORD" <<'EOSQL'
CREATE ROLE platform_app LOGIN
  PASSWORD :'app_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO platform_app', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO platform_app;

-- 迁移由 owner 建表；之后新建的表与序列自动授权给运行期账号，避免每次迁移手工补授权。
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO platform_app;
EOSQL

echo "postgres: 运行期账号 platform_app 已创建（NOBYPASSRLS）"
