# CI 流水线运行手册

| 项目 | 内容 |
|---|---|
| 任务 | WP01-01 |
| 状态 | 生效（2026-11-09） |
| 流水线 | `.github/workflows/ci.yml` |
| 证据 | `docs/evidence/wp01-01/` |

## 1. 分支策略

| 规则 | 设置 |
|---|---|
| 主干 | `main`，禁止直接推送 |
| 合并方式 | Squash merge，保留单个 Conventional Commits 头 |
| 必需检查 | `secret-scan`、`commit-lint`、`backend`、`frontend`、`desktop-agent`、`plugin-runner`、`contracts`、`tests`、`supply-chain` |
| 评审 | `CODEOWNERS` 指定路径必须由对应所有者批准；`/contracts/`、`/.github/`、`coverage-scope.yaml` 与 ADR 同级别 |
| 强制推送 | 禁止（含管理员） |
| 分支命名 | `wp<NN>-<NN>-<slug>`，例如 `wp01-01-ci-baseline` |

分支保护在仓库设置中配置，**不在代码里**；变更保护规则需走变更请求并记录在 `docs/change-request-template.md`。

## 2. 任务与本地等价命令

| 任务 | 覆盖 | 本地命令 |
|---|---|---|
| `secret-scan` | gitleaks 全历史扫描 | `gitleaks detect --redact --exit-code 1`（需先安装 CLI，或依赖 CI） |
| `commit-lint` | Conventional Commits | `npx -y -p @commitlint/cli@19 commitlint --from origin/main --to HEAD` |
| `backend` | 编译、单测、模块边界、JaCoCo、依赖漏洞、SBOM | `mvn -B -f backend/pom.xml clean verify` → `node backend/tools/coverage-gate.mjs` |
| `frontend` | 类型检查、单测、构建、依赖漏洞、SBOM | `pnpm -C frontend run typecheck && pnpm -C frontend run test && pnpm -C frontend run build` |
| `desktop-agent` | 构建（警告即错误）、隐私边界自检、依赖漏洞、SBOM | `dotnet build desktop-agent/desktop-agent.sln -c Release && dotnet run --project desktop-agent/privacy-engine/WorkAssistant.PrivacyEngine.Checks -c Release` |
| `plugin-runner` | 镜像构建、隔离冒烟、镜像漏洞扫描 | `node plugin-runner/smoke.mjs` |
| `contracts` | 三类契约覆盖与破坏性变更、门禁自测 | `node contracts/tools/contract-check.mjs && node --test contracts/tools/contract-check.test.mjs` |
| `tests` | 契约覆盖、门禁接线、破坏性变更阻断、端到端入口 | `node --test "tests/**/*.test.mjs"` |
| `supply-chain` | 文件系统依赖漏洞、聚合 SBOM | `trivy fs --severity CRITICAL,HIGH --exit-code 1 .` |
| `artifacts` | 不可变版本、制品哈希、依赖清单 | `node tools/artifact-manifest.mjs --input <目录> --output release-manifest.json` |

## 3. 门禁判定

| 门禁 | 阻断条件 | 例外方式 |
|---|---|---|
| 秘密扫描 | 命中任何规则 | 无。先吊销凭据再清理历史 |
| 依赖漏洞 | 前端 `pnpm audit` 高危及以上；Maven `CVSS ≥ 7`；.NET 任一已知漏洞 | 无自动豁免；必须修版本 |
| 容器扫描 | 镜像 `CRITICAL`/`HIGH` 且已有修复版本 | 无 |
| 模块边界 | `ApplicationModules.verify()` 失败（跨模块访问内部包） | 修改依赖声明并评审 |
| 覆盖率 | 低于 `backend/coverage-scope.yaml` 基线（容差内允许抖动） | 在发布提交上执行 `--write-baseline` |
| 契约 | 三类中任一未覆盖，或存在未批准破坏性变更 | 已批准变更请求 + 未到期 |
| 提交规范 | 任一条提交不符合类型清单 | 无（squash 时重写） |

## 4. 缓存与可复现

- 缓存**只**覆盖依赖（Maven `~/.m2`、pnpm store），不缓存 `target/`、`dist/`、`bin/`、`obj/`；`tests` 任务中有断言防止退化。
- `backend/pom.xml` 固定 `project.build.outputTimestamp`，两次 `mvn clean verify` 产出的 `boot-api` jar 与 `bom.json` 哈希一致（证据见 `docs/evidence/wp01-01/reproducible-build.txt`）。
- 镜像构建不使用构建缓存以外的手段；同一提交重复构建得到相同镜像摘要由基础镜像摘要固定保证。

## 5. 本地环境注意事项

| 现象 | 原因 | 处置 |
|---|---|---|
| `mvn` 报 JDK 1.8 不在 `[21,22)` | 机器默认 `JAVA_HOME` 指向 JDK 8 | 构建前设置 `JAVA_HOME` 为 Temurin 21 |
| `ERR_PNPM_IGNORED_BUILDS` | pnpm 12 不再读取 `package.json` 的 `pnpm` 字段 | 在 `frontend/pnpm-workspace.yaml` 的 `allowBuilds` 中放行 `esbuild` |
| `failed to connect to the docker API at unix:///var/run/docker.sock` | 引擎在 WSL `pglab`，且随 WSL 空闲退出 | 先 `wsl -d pglab -u root --exec bash -lc dockup`；再设 `PLATFORM_DOCKER='wsl -d pglab -u root --exec docker'` |
| JaCoCo 不生成 `jacoco.exec` | 工作区路径含中文时 agent 无法落盘 | 在 ASCII 路径副本构建，或直接以 CI 结果为准 |
| `pnpm audit` 报 `ERR_PNPM_AUDIT_ENDPOINT_NOT_EXISTS` | 本机 registry 指向 npmmirror，无 audit 端点 | 加 `--registry https://registry.npmjs.org/` |

## 6. 证据归档

每个工作包在 `docs/evidence/<工作包>/` 下按任务保存输出，命名 `任务名.txt`；验收结论写入 `IMPLEMENTATION_PLAN.md` 对应工作包的证据行，不得只写「已通过」。
