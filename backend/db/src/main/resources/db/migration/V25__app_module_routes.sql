-- V25 模块登记路由。
--
-- 模块可以登记一个前端菜单路由（/apps/<slug>）与一个后端 API 前缀；启用后
--   1. 前端把 route_path 动态注册为左侧「应用」菜单并渲染通用模块页；
--   2. 后端把 api_path_prefix 纳入模块停用拦截。
-- 两个字段都可为空：仅登记元数据的模块不产生菜单，也不拦截任何 API。

ALTER TABLE app_module
    ADD COLUMN route_path text,
    ADD COLUMN api_path_prefix text;

ALTER TABLE app_module
    ADD CONSTRAINT app_module_route_path_check
        CHECK (route_path IS NULL OR route_path ~ '^/apps/[a-z0-9][a-z0-9._-]{0,63}$'),
    ADD CONSTRAINT app_module_api_prefix_check
        CHECK (api_path_prefix IS NULL
               OR api_path_prefix ~ '^/api/v1/[a-z0-9][a-z0-9._-]*(/[a-z0-9._-]+)*$');

COMMENT ON COLUMN app_module.route_path IS
    '前端菜单路由：/apps/<slug>；为空表示不注册菜单';
COMMENT ON COLUMN app_module.api_path_prefix IS
    '后端 API 前缀；模块停用时命中该前缀的请求返回 403 MODULE_DISABLED';

CREATE UNIQUE INDEX app_module_route_path_unique
    ON app_module (tenant_id, route_path) WHERE route_path IS NOT NULL;

CREATE UNIQUE INDEX app_module_api_prefix_unique
    ON app_module (tenant_id, api_path_prefix) WHERE api_path_prefix IS NOT NULL;
