# WP02-02 证据索引

对应验收项：User、TenantMember、Actor、Person、Employment 分离；邀请/激活/密码策略/MFA/会话/设备/API 令牌/服务账号与撤销；高权限强制 MFA；主体禁用或 `auth_version` 变化后会话及时失效；OIDC/SAML/SCIM 只预留模型，不阻塞 P0。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`；`IdentityDbTest` 本地无库按设计跳过（1 skipped）；`PasswordPolicyTest` 6、`SessionServiceTest` 6、`TotpTest` 5、`TokenHasherTest` 3、`IdentityProviderServiceTest` 3、`AuthApiTest` 7、`TenantApiBoundaryTest` 8、`RequestContextBindingFilterTest` 4、`ModuleStructureTest` 2 全部通过 |
| `db-verify.txt` | `scripts/db-verify.ps1` 八个步骤完整输出 | 空库与升级库 V1–V7 校验和一致（V7 `-638483512`）；RLS 9/9；31 张租户表 ENABLE+FORCE；`70-identity.sql` 打印「WP02-02 身份与会话数据库断言通过」；`IdentityDbTest` `Tests run: 1, Failures: 0, Errors: 0`；连接池隔离 3/3 |
| `identity-scenarios.txt` | `IdentityDbTest` 15 个场景清单 | 无账号员工、邀请一次性、密码登录、撤销/过期/`auth_version`/跨租户拒绝、一人多雇佣与再雇佣、账号更换、服务账号令牌、MFA 全流程、身份提供商 draft/active、无上下文 16 表 0 行 |
| `auth-negative.txt` | boot-api 认证切片与边界负例 | `AuthApiTest` 7/7：Cookie 属性、MFA 202 不下发会话、`SESSION_REQUIRED`、伪造 `X-Subject-Id` 无效、CSRF 缺失 403、CSRF 匹配 204、`/me`；`TenantApiBoundaryTest` 8/8 改为 Bearer |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` | 7 份契约全部 PASS；OpenAPI 1.3.0 新增 34 条路径与 14 个 schema，删除 `X-Subject-Id` 后基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 7 个报告，指令覆盖率 18.51%（covered=3935, missed=17323），基线 0.00%，`PASS` |

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'

# 真实 PostgreSQL：迁移、RLS、租户生命周期、身份与会话、连接池
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 契约与覆盖率门禁
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```

> 本机工作区路径含中文（`新建文件夹`），JaCoCo agent 经 `cmd.exe` 分叉时会把 `destfile` 的 UTF-8 路径写坏。用 `-Djacoco.destFile` 指向 ASCII 路径即可；CI 的 Linux 工作区不需要这个参数。

## 过程记录：失败与修复

**1. 裸 JDBC 查不到租户行（RLS 生效的必然结果）**

`IdentityDbTest` 首轮在会话/令牌/历史查询上返回 0 行。根因是测试直接走 `JdbcTemplate`，没有进入租户事务，`app.tenant_id` 未设置，RLS 过滤掉全部行。
修复：所有 DB 访问包进 `withContext(tenantId, ...)`，内部统一走 `TenantTransactions.inTenant(...)`；同时修正 `bumpAuthVersion`、`UPDATE identity_session`、邀请插入与历史计数。
复测：15 个场景全部通过。

**2. 过期演练违反 `expires_at > created_at`**

直接改 `expires_at` 到过去触发 CHECK 约束。
修复：同时把 `created_at`、`idle_expires_at`、`expires_at` 调整到过去。

**3. 账号更换后的旧会话实际是「已撤销」**

测试原按 `SESSION_INVALID` 断言，实际 `changeAccount` 会显式撤销旧账号全部会话。
修复：断言改为 `SESSION_REVOKED`；令牌撤销同理改为 `TOKEN_REVOKED`。

**4. 新账号无法登录**

`insertInvited` 建出的账号处于未激活状态。
修复：测试补 `accounts.activate(newUserId, hash)` 与密码历史写入。

**5. 成员强制 MFA 未传导到新账号（真实缺陷）**

`AccountService.changeAccount` 只改了 `identity_tenant_member.user_id`，新账号的 `mfa_required` 仍为 false，导致 `mfaService.disable` 没有返回 409。
修复：换号后同步 `userAccountStore.setMfaRequired(newAccount.id(), member.mfaRequired())`。
复测：`MFA_REQUIRED_FOR_PRIVILEGED` 正常返回。

**6. `@WebMvcTest` 加载认证过滤器导致缺 Bean**

`AuthenticationFilter` 是 `@Component`，切片测试会加载它。
修复：相关切片补 `@MockitoBean SessionService`、`@MockitoBean IdentityProperties`，并在 `@BeforeEach` stub Cookie 名与令牌认证；租户边界用例统一改 Bearer。

**7. 模块依赖白名单**

`organization` 需要 `platform`、`identity` 需要 `organization` 与 `platform`。
修复：更新两个模块 `package-info.java` 的 `allowedDependencies`；`ModuleStructureTest` 2/2 通过。

## 边界

- 邮件发送、邀请落地页与密码找回邮件属 P1；当前令牌由创建接口一次性返回。
- OIDC/SAML/SCIM 实际登录与 SCIM 同步属 P1；P0 只落模型与配置，`active` 返回 409。
- `/api/v1/people/**` 读接口只要求登录，细粒度数据范围与字段权限由 WP02-04 收口。
- 平台域 `/platform/**` 仍用 `X-Platform-Operator-Id` 占位头，独立认证域属 WP02-07。
