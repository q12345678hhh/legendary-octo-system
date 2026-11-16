# WP02-02 身份与会话 实施方案

## Context

WP02-01 已合并（`594638f`），租户生命周期可用，但认证仍是占位：`RequestContextBindingFilter` 直接信任 `X-Subject-Id`，任何调用方都能伪造主体；`modules/identity` 只有 `package-info.java`；数据库无账号、会话、MFA、令牌表；前端无登录代码。

本工作包要把占位认证替换为真实身份体系，满足 `IMPLEMENTATION_PLAN.md` WP02-02：

- User / TenantMember / Actor / Person / Employment 分离；
- 邀请、激活、密码策略、MFA、会话、设备、API 令牌、服务账号、撤销；
- 高权限强制 MFA；主体禁用或 `auth_version` 变化后会话及时失效；
- 预留 OIDC/SAML/SCIM 模型，不接 P1 连接器；
- 验收：无账号员工、再雇佣、一人多雇佣、账号更换；过期/撤销/跨租户令牌全部拒绝。

已确认的三个决策：

1. 认证用**自建会话过滤器 + `spring-security-crypto`**，不引入完整 Spring Security 过滤链。
2. Person / Employment 放 **organization 模块**，identity 只存账号、成员、会话与令牌。
3. 邀请/激活令牌由**创建接口直接返回一次性明文**，库里只存哈希；P1 接邮件后改为只投递。

## 1. 数据模型（V7 迁移）

新增 `backend/db/src/main/resources/db/migration/V7__identity_and_session.sql`，全部租户表照抄 `backend/db/templates/tenant-table.template.sql`：`tenant_id` 外键、`ENABLE + FORCE RLS`、同名 `*_isolation` 策略。

### identity 模块（14 张）

| 表 | 关键字段与约束 |
| --- | --- |
| `identity_actor` | `id`、`actor_type`（user/service/ai/external）、`display_name`、`status`、`user_id`/`service_account_id` 可空；`(tenant_id,user_id)`、`(tenant_id,service_account_id)` 部分唯一 |
| `identity_user_account` | `email_normalized`、`display_name`、`password_hash`、`status`（invited/active/suspended/disabled）、`auth_version`、`mfa_required`、`failed_attempts`、`locked_until`、`activated_at`、`disabled_at`；`(tenant_id,email_normalized)` 唯一 |
| `identity_tenant_member` | `user_id`、`person_id` 可空、`status`、`is_owner`、`is_admin`、`mfa_required`；`(tenant_id,user_id)` 唯一，`person_id` 非空时 `(tenant_id,person_id)` 唯一 |
| `identity_member_account_history` | `member_id`、`previous_user_id`、`new_user_id`、`changed_by`、`reason`、`changed_at`：账号更换证据 |
| `identity_password_history` | `user_id`、`password_hash`、`created_at`：禁止复用最近 5 个 |
| `identity_invitation` | `user_id`、`member_id`、`email`、`token_hash` 唯一、`expires_at`、`accepted_at`、`revoked_at`、`created_by` |
| `identity_mfa_method` | `method_type='totp'`、`secret_ciphertext`、`secret_nonce`、`status`（pending/active/disabled）、`confirmed_at`、`last_used_step` |
| `identity_recovery_code` | `user_id`、`code_hash`、`used_at`；每用户 10 条 |
| `identity_mfa_challenge` | `user_id`、`token_hash` 唯一、`expires_at`、`attempts`、`consumed_at`：登录第二因子短期票据 |
| `identity_session` | `user_id`、`actor_id`、`token_hash` 唯一、`auth_version`、`mfa_satisfied`、`device_id` 可空、`device_label`、`ip`、`user_agent`、`created_at`、`last_seen_at`、`idle_expires_at`、`expires_at`、`revoked_at`、`revoked_reason`、`revoked_by` |
| `identity_api_token` | `service_account_id`、`actor_id`、`name`、`token_hash` 唯一、`prefix`、`scopes text[]`、`expires_at`、`revoked_at`、`last_used_at`、`created_by` |
| `identity_service_account` | `actor_id`、`name`、`description`、`status`；`(tenant_id,name)` 唯一 |
| `identity_identity_provider` | `provider_type`（oidc/saml/scim）、`name`、`config jsonb`、`status`（draft/disabled/active）；`(tenant_id,provider_type,name)` 唯一 |
| `identity_auth_event` | `actor_id`/`user_id`、`event_type`、`outcome`、`ip`、`user_agent`、`detail jsonb`、`correlation_id`、`trace_id`、`occurred_at` |

### organization 模块（2 张）

| 表 | 关键字段与约束 |
| --- | --- |
| `organization_person` | `display_name`、`primary_email` 可空、`status`（active/inactive）；自然人，不登录 |
| `organization_employment` | `person_id`、`employment_no`、`status`（active/ended）、`effective_from`、`effective_to`、`job_title` 可空；`(tenant_id,employment_no)` 唯一，`effective_to > effective_from` |

Person 与 Employment 分离后：无账号员工 = 有 Person/Employment、无 TenantMember；一人多雇佣 = 同一个 Person 多条 Employment；再雇佣 = 旧 Employment 置 `ended` 后新增一条；账号更换 = 同一 TenantMember 换 `user_id` 并写 `identity_member_account_history`。

## 2. 模块与代码结构

### `backend/modules/identity`

扩展 `pom.xml`：新增 `com.acme.platform:platform`、`tenancy`、`organization`、`org.springframework.security:spring-security-crypto`、`tools.jackson.core:jackson-databind`、测试用 `spring-boot-starter-test` 与 `postgresql`。

按现有 tenancy 风格分包（`Store` 只写 SQL，事务由调用方经 `TenantTransactions.inTenant(...)` 控制）：

- `account/`：`UserAccount`、`UserStatus`、`UserAccountStore`、`AccountService`、`PasswordPolicy`、`PasswordHasher`
- `actor/`：`Actor`、`ActorType`、`ActorStore`、`ActorService`
- `member/`：`TenantMember`、`MemberStatus`、`MemberStore`、`MemberService`
- `session/`：`Session`、`SessionStore`、`SessionService`、`SessionAuthenticator`、`SessionPrincipal`、`TokenHasher`
- `mfa/`：`MfaMethod`、`MfaService`、`Totp`（RFC 6238，HmacSHA1）、`TotpSecretCipher`（AES-256-GCM）、`RecoveryCodes`
- `token/`：`ApiToken`、`ApiTokenStore`、`ApiTokenService`、`ServiceAccount`、`ServiceAccountService`
- `invitation/`：`Invitation`、`InvitationService`
- `provider/`：`IdentityProvider`、`IdentityProviderService`（`active` 一律 409 `CONNECTOR_NOT_AVAILABLE`）
- `audit/`：`AuthEvent`、`AuthEventStore`
- `auth/`：`AuthenticationService`（编排登录、MFA 挑战、登出、`/me`）
- `IdentityConfiguration`、`IdentityProperties`

关键规则：

- BCrypt strength 12（`BCryptPasswordEncoder`），密码长度 12–128、四类字符至少三类、拒绝常见弱口令与邮箱本地部分、最近 5 个不可复用。
- 会话令牌 32 字节 `SecureRandom` → Base64URL；库里只存 SHA-256；绝对 8 小时、空闲 30 分钟；`last_seen_at` 最多每分钟更新一次。
- 每次校验会话都联查 `identity_user_account`：`status='active'` 且 `session.auth_version = user.auth_version`，否则 401 并撤销该会话。
- TOTP 密钥 AES-256-GCM 加密，密钥来自 `platform.identity.mfa-key`（base64 32 字节）；缺失时注册 MFA 返回 503，不影响无 MFA 用户登录。
- 登录失败 5 次锁定 15 分钟（`failed_attempts`/`locked_until`）。
- 用户已有 active MFA → 登录返回 202 + `mfaToken`，验证后才发会话；没有 MFA 但 `member.mfa_required` → 允许登录，但 `mfaSatisfied=false`，高权限接口 403 `MFA_REQUIRED`。

### `backend/modules/organization`

新增 `Person`、`Employment`、两个 Store 与 `PeopleService`，只依赖 `platform`；不反向依赖 identity。

### `backend/boot-api`

- 改 `RequestContextBindingFilter`：保留关联 ID/trace/租户解析，**删除 `X-Subject-Id` 读取**；只写租户与关联 ID。
- 新增 `AuthenticationFilter`（在 `RequestContextBindingFilter` 之后、`IdempotencyFilter` 之前）：
  - 公开路径放行：`POST /api/v1/auth/login`、`/auth/mfa/verify`、`/auth/invitations/accept`、`/api/v1/system/health`、`/actuator/**`、`/platform/**`（平台域仍走 `X-Platform-Operator-Id`，真实认证属 WP02-07）。
  - 其余请求必须带 `X-Tenant-Id`；缺失 401 `TENANT_REQUIRED`。
  - 从 `plt_session` Cookie 或 `Authorization: Bearer <token>` 解析；`SessionAuthenticator` 在租户事务内按哈希查会话/令牌，跨租户自然 0 行 → 401。
  - 成功后重建 `RequestContext(subjectId=actorId)`，并写入新增的 `ActorContextHolder`（`platform` 模块）供服务层读取 `actorId/userId/memberId/actorType/mfaSatisfied/sessionId`。
  - Cookie 认证的非 GET 请求必须带 `X-CSRF-Token`，与 `plt_csrf` Cookie 双提交一致；Bearer 跳过。
  - 登录/验证成功下发 `plt_session`（HttpOnly、Path=/、SameSite=Lax、`Secure` 可配）与 `plt_csrf`；登出清除。
- 新增控制器：`AuthController`、`MemberAdminController`、`ServiceAccountController`、`IdentityProviderController`、`PeopleController`。
- 更新文案：`TenantController`、`WorkAssistantController`、`platform` 模块里所有「必须携带 X-Subject-Id」改为「必须已认证」。

### `backend/modules/platform`

新增 `ActorContext` + `ActorContextHolder`（与 `RequestContextHolder` 同风格），避免 identity 反向依赖 boot-api。

## 3. API 与契约

新增约 30 条路径，契约版本升到 **1.3.0**，随后重跑 `contracts/tools/contract-check.mjs --update` 重冻结基线。

- 公开：`POST /api/v1/auth/login`、`POST /api/v1/auth/mfa/verify`、`POST /api/v1/auth/invitations/accept`（请求体含 `tenantId`、令牌、密码）。
- 会话：`POST /auth/logout`、`GET /me`、`POST /auth/password/change`、`GET /auth/sessions`、`DELETE /auth/sessions/{id}`、`POST /auth/sessions/revoke-all`。
- MFA：`GET /auth/mfa`、`POST /auth/mfa/totp/start`、`POST /auth/mfa/totp/confirm`、`POST /auth/mfa/disable`。
- 成员：`GET /members`、`POST /members/invitations`、`PATCH /members/{id}`（启停/管理员/强制 MFA）、`PATCH /members/{id}/account`、`POST /members/{id}/reset-password`。
- 服务账号与令牌：`GET/POST /service-accounts`、`GET/POST /service-accounts/{id}/tokens`、`DELETE /service-accounts/{id}/tokens/{tokenId}`。
- 身份提供商占位：`GET/POST /identity-providers`、`PATCH /identity-providers/{id}`。
- 人员与雇佣：`GET/POST /people`、`GET /people/{personId}`、`POST /people/{personId}/employments`、`PATCH /employments/{employmentId}/end`。

管理类接口在 WP02-04 之前用 `member.is_admin`/`is_owner` 做最小授权；高权限动作（邀请、停用、重置密码、创建服务账号/长期令牌、改身份提供商、关闭 MFA）要求 `mfaSatisfied=true`，否则 403 `MFA_REQUIRED`。

## 4. 需要同步修改的既有文件

- `backend/db/src/main/resources/db/migration/V7__identity_and_session.sql`（新增）
- `tests/db/40-integrity.sql`：注册 16 张新表，期望租户表从 15 改为 **31**
- `tests/db/70-identity.sql`（新增）：RLS、同邮箱跨租户、成员/雇佣唯一约束、无上下文 0 行
- `scripts/db-verify.ps1`：版本数 6 → **7**；新增步骤 5c（跑 `70-identity.sql` + `IdentityDbTest`，沿用 5b 的聚合 POM reactor 写法，避免同仓依赖解析失败）
- `backend/modules/identity/pom.xml`、`backend/modules/organization/pom.xml`
- `backend/boot-api/.../RequestContextBindingFilter.java`、`application.yml`（会话/MFA/Cookie 配置项）
- `backend/boot-api/src/test/.../TenantApiBoundaryTest.java`、`RequestContextBindingFilterTest.java`、`ModuleStructureTest.java`：改用真实会话/模拟 `SessionAuthenticator`，并新增「禁用 `X-Subject-Id` 后该头不再生效」的负例
- `contracts/openapi/platform-api.v1.json`、`contracts/baseline/contracts-baseline.json`

## 5. 测试与证据

### 单元/切片

- `PasswordPolicyTest`：长度、字符类别、弱口令、历史复用。
- `TotpTest`：RFC 6238 已知向量、±1 时间窗、重放步长拒绝。
- `SessionServiceTest`：过期、空闲超时、撤销、`auth_version` 变化、用户禁用。
- `TokenHasherTest`：哈希稳定、明文不落库。
- `MfaServiceTest`：pending → active、恢复码一次性、高权限不可关闭。
- `InvitationServiceTest`：72 小时过期、一次性、邮箱不匹配。
- `IdentityProviderServiceTest`：`active` 返回 409。
- `AuthApiTest`（`@WebMvcTest`）：登录/挑战/验证/登出、Cookie 属性、CSRF 缺失 403、`X-Subject-Id` 被忽略、跨租户 Cookie 401。
- `PeopleApiTest`：无账号员工、再雇佣、一人多雇佣、账号更换。

### 真实 PostgreSQL（`-Dplatform.db.url` 时执行）

`IdentityDbTest` 单方法覆盖：

1. 租户 A/B 各建用户、Actor、Member；
2. Person + 两条 active Employment（一人多雇佣）；无账号 Person；
3. 旧 Employment `ended` 后新增（再雇佣）；
4. Member 换 `user_id` 并留历史（账号更换）；
5. 会话有效 → 过期/撤销/`auth_version` 变化全部拒绝；
6. A 的会话在 B 上下文查不到（跨租户令牌拒绝）；
7. 邀请接受一次成功、二次失败、过期失败；
8. 16 张新表无租户上下文全部 0 行。

### 命令

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1
node contracts/tools/contract-check.mjs
mvn -B -ntp -f backend/pom.xml verify `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'
node backend/tools/coverage-gate.mjs
```

### 证据与文档

- `docs/runbooks/identity-session.md`：登录、MFA 注册/找回、会话与设备撤销、邀请、API 令牌、账号更换、事故处置。
- `docs/evidence/wp02-02/`：`README.md`、`backend-tests.txt`、`db-verify.txt`、`auth-negative.txt`（跨租户/过期/撤销/CSRF/占位头）、`identity-scenarios.txt`（无账号/再雇佣/多雇佣/换号）、`contract-check.txt`、`coverage-gate.txt`。
- `IMPLEMENTATION_PLAN.md`：勾选 WP02-02 四项，补「完成情况」表与遗留说明。
- 迁移说明补充到 `docs/runbooks/db-migration.md` 的版本表（如该文件维护版本清单）。

## 6. 范围外

- 真实 OIDC/SAML/SCIM 连接器与邮件投递（P1）；本包只建模型并拒绝 `active`。
- 角色、权限、数据范围与职责分离（WP02-04/02-05）；本包只用 `is_admin`/`is_owner`。
- 平台运营端独立认证域（WP02-07）；`X-Platform-Operator-Id` 保持不变。
- 前端登录页：`frontend/src` 仍为空，本包只交付 API 与契约。
- 设备指纹与风险评分：只记录 `device_id`/`device_label`/`user_agent`/`ip`，不做风控模型。

## 7. 实施顺序

1. V7 迁移 + `40-integrity.sql` + `70-identity.sql` + `db-verify.ps1` 版本数。
2. identity/organization 模块依赖与领域服务；platform 的 `ActorContext`。
3. boot-api 认证过滤器、Cookie/CSRF、控制器。
4. 单元/切片测试，更新既有占位头测试。
5. 运行真实 PostgreSQL 用例并修正迁移。
6. 契约扩展 + 基线重冻结。
7. 运行手册、证据、`IMPLEMENTATION_PLAN.md`。
8. 分支 → Conventional Commit → PR → 11 项 CI 全绿 → squash 合并。
