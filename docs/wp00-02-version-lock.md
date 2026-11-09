# 技术与版本锁定表

| 项目 | 内容 |
|---|---|
| 任务 | T00-02-2 |
| 依据 | `PLAN.md` §12.1–§12.3 |
| 状态 | **已锁定（2026-11-09）**：T00-02-2 的 9 项必需工具全部实测回填；应用层版本（Spring Boot、Modulith、React/Ant Design、Vite/Vitest）已于 WP01-01 回填，见 §8 |
| 约束 | 回填前不得作为构建依据；锁定后写入 Maven/`package.json`/`global.json` 与 ADR |

## 1. 后端

| 能力 | 选择 | 候选版本 | 锁定规则 |
|---|---|---|---|
| JDK | Java 21 | Temurin 21（LTS） | 必须为 LTS，禁用预览特性 |
| 构建 | Maven | 3.9.x | 与 JDK 21 兼容 |
| 框架 | Spring Boot | 3.3.x 或 3.4.x 中仍受安全支持的补丁版 | **已锁定 3.4.13**（`spring-boot-starter-parent`，3.4 线最新补丁） |
| 模块化 | Spring Modulith + Maven 多模块 | 与 Spring Boot 版本对齐 | **已锁定 1.3.12**（与 Boot 3.4 对齐），以 BOM import 管理 |
| 安全 | Spring Security / OAuth2 Resource Server | 随 Boot BOM | 不自选版本 |
| 数据访问 | Spring Data JPA + jOOQ/JdbcClient | 随 Boot BOM | 禁止动态拼接 SQL |
| 数据库 | PostgreSQL | 16.x | RLS 为必需能力，不用低版本替代 |
| 迁移 | Flyway | 随 Boot BOM 或独立锁定 | 迁移文件集中在 `backend/db/` |
| 缓存 | Redis | 7.x | 仅用于短缓存、限流、锁提示 |
| 对象存储 | MinIO（本地） | 最新稳定版 | 生产用 S3 兼容服务，本地仅开发 |
| API | OpenAPI 3.1 + SSE | — | 首期不引入 GraphQL |

## 2. 前端

| 能力 | 选择 | 候选版本 | 备注 |
|---|---|---|---|
| 运行时 | Node.js | 22 LTS | 与 Vite 兼容性以官方为准 |
| 包管理 | pnpm | 9.x | workspace 管理 `frontend/apps/*` 与 `packages/*`；实际锁定 12.3.4（见 §8） |
| 基础 | React + TypeScript（strict）+ Vite | React 18/19、TS 5.x | **已锁定 React 18.3.1 + TS 5.9.3 + Vite 6.4.5** |
| UI | Ant Design | 5.x | **已锁定 5.29.3**，design token 层在 `@platform/design-system` |
| 表单 | React Hook Form + Zod | 最新稳定 | 服务端仍为最终校验 |
| 请求/状态 | TanStack Query、React Router、Zustand（有限） | 最新稳定 | 服务端状态不复制到全局 store |
| 图表 | Apache ECharts | 5.x | 大数据服务端分页 |
| 测试 | Vitest、Testing Library、Playwright | 最新稳定 | **已锁定 Vitest 4.1.12 + @testing-library/react 16.3.3**；Playwright 待 WP01-02 |

## 3. Windows 工作助手

| 能力 | 选择 | 候选版本 | 备注 |
|---|---|---|---|
| 运行时 | .NET | 8（LTS） | 需确认目标机已有运行时或随包分发 |
| 宿主 | WPF 或 WinUI 3 | 与 .NET 8 匹配 | 决策写入 ADR，不在安装阶段临时改 |
| 容器 | WSL2 内 Docker Engine（不装 Docker Desktop） | 29.1.x（Ubuntu 24.04 包） | 承载 PostgreSQL、Redis、MinIO；镜像经国内镜像站拉取 |

## 4. 安装前必须回填的项

1. Spring Boot 具体补丁版本——已回填 3.4.13（WP01-01）。
2. PostgreSQL 小版本与是否启用 `pgvector`——小版本已回填 16.15；`pgvector` 未启用（尖峰 B 未使用）。
3. Node 22 的补丁版本与 pnpm 主版本——已回填为 Node 24.22.1 / pnpm 12.3.4。
4. React 与 Ant Design 的兼容组合——已回填 React 18.3.1 + antd 5.29.3（WP01-01）。
5. .NET 8 的具体 SDK 补丁版本——已回填 8.0.427。

回填要求：每项写明版本号、来源（官方发布页）、确认日期；同一项不得出现两个并存版本。

## 5. 实测盘点结果（2026-11-09，实测回填）

| 项 | 实测 | 与候选差异 | 结论 |
|---|---|---|---|
| JDK | OpenJDK 21.0.12.1 LTS（Temurin 21.0.12.1+1） | 一致 | 可用 |
| Maven | 3.8.8 | 候选 3.9.x，偏低 | 3.8.8 已跑通骨架（`mvn -B -q validate` 通过）；G1 前升级到 3.9.x，插件兼容风险 |
| Node | v24.22.1 | 候选 22 LTS | 接受。当前 LTS 线，回填此差异 |
| pnpm | 12.3.4 | 候选 9.x | 接受。回填实际主版本 |
| .NET SDK | **8.0.427**（2026-11-09 安装，`C:\Program Files\dotnet`） | 已补齐 | 可用；`dotnet new/build/run` 冒烟通过 |
| Docker | CLI 29.9.2；WSL2 `pglab` 内 Engine 29.1.3 + Compose 2.40.3（overlayfs、cgroup v2、containerd snapshotter） | CLI 与引擎分处 Windows/WSL 两个环境 | **可用**：镜像经 `docker.m.daocloud.io` 拉取，宿主代理由 `dockup` 注入；`postgres:16` 拉取并运行验证通过。证据 `docs/evidence/docker-engine/verify-output.txt` |
| PostgreSQL | 16.15（WSL `pglab`，端口 5433） | 一致 | 可用；Windows 侧无 psql，统一走 WSL |
| psql 客户端 | WSL 内 16.15 可用 | — | 不阻塞；尖峰 A/B/C/D 均已在 WSL 执行 |
| Git | 2.55.0.windows.3 | — | 可用 |
| VS Code | 1.130.0 | — | 可用 |
| Python | 3.14.0 | 新增（尖峰 E 用） | 仅测试脚本依赖，不进入产品运行时 |
| WSL | 2.7.11.0，Ubuntu 24.04.5 LTS | 新增 | 尖峰数据库与隔离验证载体 |
| 操作系统 | Windows 11 专业版 26200 | — | 满足 |
| 内存 | 31.6 GB | — | 满足 |
| C 盘可用空间 | 26.2 GB | 无候选基线 | **偏紧**：Docker 镜像、Maven 本地库、node_modules 共同占用，G1 前需给磁盘预算 |

## 6. 安装与处置清单

| 动作 | 状态 | 影响 |
|---|---|---|
| 安装 .NET 8 SDK 8.0.427 | **已完成（2026-11-09，用户授权）** | 系统级安装；`dotnet` 与既有运行时同根 |
| Docker 引擎与镜像源 | **已完成（2026-11-09）** | WSL2 `pglab` 内 Engine 29.1.3 + `docker.m.daocloud.io` 镜像站；`dockup` 幂等启动并注入宿主代理，`dockpull` 带重试；`postgres:16` 已验证可运行 |
| Maven 升级至 3.9.x | 待授权 | 替换 3.8.8，需改 PATH |
| 磁盘预算与清理 | 待处置 | 目标 C 盘可用 ≥ 60 GB |
| psql 客户端（Windows） | 不安装 | 统一走 WSL，避免两套版本并存 |

## 7. 锁定值（T00-02-2 交付）

| 能力 | 锁定值 | LTS | 来源 | 确认日期 |
|---|---|---|---|---|
| JDK | Temurin 21.0.12.1+1 | 是 | 本机 `java -version` | 2026-11-09 |
| Maven | 3.8.8（G1 前升 3.9.x） | — | 本机 `mvn -v` | 2026-11-09 |
| Node.js | 24.22.1 | 是 | 本机 `node -v` | 2026-11-09 |
| pnpm | 12.3.4 | — | 本机 `pnpm -v` | 2026-11-09 |
| .NET SDK | 8.0.427 | 是 | `dotnet --list-sdks` | 2026-11-09 |
| Docker | CLI 29.9.2 / Engine 29.1.3（WSL2 pglab） | — | `docker version` | 2026-11-09 |
| PostgreSQL | 16.15 | — | WSL `psql --version` | 2026-11-09 |
| Git | 2.55.0.windows.3 | — | 本机 `git --version` | 2026-11-09 |
| VS Code | 1.130.0 | — | 本机 `code --version` | 2026-11-09 |

**仍待回填（不进 T00-02-2 范围，G1 前完成）**：Flyway 与 Redis 小版本。回填要求不变：写明版本号、官方来源与确认日期，同一项不得并存两个版本。

## 8. 应用层锁定值（WP01-01 回填）

| 能力 | 锁定值 | 声明位置 | 确认日期 |
|---|---|---|---|
| Spring Boot | 3.4.13（`spring-boot-starter-parent`） | `backend/pom.xml` | 2026-11-09 |
| Spring Modulith | 1.3.12（`spring-modulith-bom`） | `backend/pom.xml` | 2026-11-09 |
| JaCoCo | 0.8.16 | `backend/pom.xml` | 2026-11-09 |
| CycloneDX Maven 插件 | 2.9.3 | `backend/pom.xml` | 2026-11-09 |
| maven-enforcer | 3.7.0 | `backend/pom.xml` | 2026-11-09 |
| React / React DOM | 18.3.1 | `frontend/apps/web/package.json` | 2026-11-09 |
| Ant Design | 5.29.3 | `frontend/apps/web/package.json` | 2026-11-09 |
| Vite | 6.4.5 | `frontend/apps/web/package.json` | 2026-11-09 |
| TypeScript | 5.9.3 | `frontend/tsconfig.base.json` 调用方一致 | 2026-11-09 |
| Vitest | 4.1.12（低于 4.1.11 存在 GHSA 高危漏洞） | `frontend/**/package.json` | 2026-11-09 |
| @testing-library/react | 16.3.3 | `frontend/apps/web/package.json` | 2026-11-09 |
| Node（实测） | 24.22.1 | `frontend/package.json` engines | 2026-11-09 |
| pnpm | 12.3.4 | `frontend/package.json` `packageManager` | 2026-11-09 |
| .NET SDK | 8.0.427（`net8.0`） | `desktop-agent/Directory.Build.props` | 2026-11-09 |
| Python（插件执行器基础镜像） | 3.12-slim | `plugin-runner/Dockerfile` | 2026-11-09 |

版本变更要求：任一锁定值升级必须同时更新本表与对应构建文件，并且通过 CI 的依赖漏洞与契约门禁。

