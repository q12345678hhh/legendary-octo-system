# 身份与会话运行手册（WP02-02）

本手册覆盖账号、租户成员、主体、会话、MFA、邀请、API 令牌、服务账号，以及
自然人（Person）与雇佣关系（Employment）的分层模型。租户隔离仍由 RLS 强制：
所有身份表都带 `tenant_id`，且 `ENABLE + FORCE ROW LEVEL SECURITY`。

本地开发栈：API `http://127.0.0.1:18080`，数据库在 WSL/Docker 内的 `platform-pg-dev`。

## 0. 边界与禁止事项

| 项 | 现状 | 约束 |
|---|---|---|
| 认证主体来源 | `plt_session` Cookie 或 `Authorization: Bearer <token>` | `X-Subject-Id` 已废弃；携带也不会被采信 |
| 平台域 | `/platform/**` 仍用 `X-Platform-Operator-Id` | 独立认证域属 WP02-07；不要把会话 Cookie 复用为平台运营身份 |
| OIDC / SAML / SCIM | 只有模型与配置接口，状态限 `draft`/`disabled` | 传 `active` 返回 `409 CONNECTOR_NOT_AVAILABLE`；P1 才接连接器 |
| 邮件投递 | 无邮件服务 | 邀请与重置令牌由创建接口一次性返回，必须由管理员安全转交 |
| Person 与账号 | 刻意分离 | 无账号员工只建 Person + Employment；账号只是可选绑定 |
| 直接改库 | — | **禁止**手工改 `identity_user_account.auth_version`、`status` 或删会话行；这会让审计与失效语义失真 |

## 1. 分层模型

```
Person（自然人，可无账号）
  └── Employment（雇佣关系，一人多段、可再雇佣）
TenantMember（租户内成员：owner/admin/mfaRequired）
  ├── UserAccount（登录账号：邮箱、密码哈希、auth_version、锁定）
  └── Actor（主体：user / service / ai / external；可被禁用）
```

- 一个账号可以更换绑定的成员：`PATCH /api/v1/members/{memberId}/account`，
  旧账号停用、会话撤销，变更写 `identity_member_account_history`。
- 同一 Person 可以有多个 Employment（兼职/多实体）；再雇佣就是新增一段 Employment。
- `tenant_id` 永远来自请求头并经 RLS 校验，不从令牌或请求体推断。

## 2. 登录、MFA 与会话

```powershell
# 1) 邮箱密码登录（必须带租户头）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "Content-Type: application/json" `
  -d '{"email":"admin@demo.test","password":"Str0ng-Passw0rd!"}' `
  "http://127.0.0.1:18080/api/v1/auth/login"

# 2) 若返回 202 + mfaToken，再验证 TOTP 或恢复码
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "Content-Type: application/json" `
  -d '{"mfaToken":"<mfaToken>","code":"123456"}' `
  "http://127.0.0.1:18080/api/v1/auth/mfa/verify"

# 3) 当前主体
curl.exe -H "X-Tenant-Id: <tenant>" -b cookies.txt `
  "http://127.0.0.1:18080/api/v1/auth/me"
```

登录成功响应：

- `Set-Cookie: plt_session=...; Path=/; HttpOnly; SameSite=Lax`（绝对 8 小时）
- `Set-Cookie: plt_csrf=...; Path=/; SameSite=Lax`（双提交令牌）
- 响应体 `status=session`，含 `principal` 与 `sessionExpiresAt`

规则：

| 项 | 值 |
|---|---|
| 会话绝对有效期 | 8 小时（`PLATFORM_IDENTITY_SESSION_TTL`） |
| 空闲超时 | 30 分钟（`PLATFORM_IDENTITY_IDLE_TTL`） |
| 登录失败锁定 | 5 次后锁 15 分钟，返回 `423 ACCOUNT_LOCKED` |
| MFA 挑战票据 | 5 分钟、最多 10 次尝试，一次性消费 |
| 会话令牌存储 | 32 字节随机明文下发；库中只存 SHA-256 |
| 高权限 MFA | owner/admin 或 `mfaRequired` 成员必须本次会话完成 MFA |

**浏览器写操作必须带 CSRF**：从 `plt_csrf` Cookie 读值，放到 `X-CSRF-Token` 头。
缺失或不一致返回 `403 CSRF_REQUIRED`。Bearer 令牌不依赖 Cookie，跳过 CSRF。

## 3. 会话失效与撤销

```powershell
# 列出自己的活跃会话
curl.exe -H "X-Tenant-Id: <tenant>" -b cookies.txt "http://127.0.0.1:18080/api/v1/auth/sessions"

# 撤销某个会话
curl.exe -X DELETE -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  "http://127.0.0.1:18080/api/v1/auth/sessions/<sessionId>"

# 撤销全部会话（登出所有设备）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  "http://127.0.0.1:18080/api/v1/auth/sessions/revoke-all"
```

失效顺序与错误码（都会在下一个请求立即生效）：

| 触发 | 错误码 |
|---|---|
| 会话被显式撤销 | `SESSION_REVOKED` |
| 绝对或空闲超时 | `SESSION_EXPIRED` |
| `auth_version` 变化、账号/成员/主体状态异常 | `SESSION_INVALID` |
| 成员被停用 | `MEMBER_INACTIVE` |
| 主体被禁用 | `ACTOR_DISABLED` |
| 服务账号被停用 | `SERVICE_ACCOUNT_DISABLED` |
| 令牌被撤销 / 过期 / 不存在或跨租户 | `TOKEN_REVOKED` / `TOKEN_EXPIRED` / `TOKEN_INVALID` |

`auth_version` 是代际失效开关：改密、重置密码、停用账号都会递增它，
所有既有会话与令牌在下一次校验时被拒，无需逐个删除。

## 4. 密码策略

- 长度 12–128；
- 大写、小写、数字、符号至少三类；
- 拒绝常见弱口令（`password123`、`qwerty` 等）；
- 不能包含邮箱 `@` 前本地部分（≥3 字符时）；
- 不能复用最近 5 个密码（`identity_password_history`）。

违反返回 `422 PASSWORD_POLICY`，复用返回 `422 PASSWORD_REUSED`。
改密成功会撤销全部会话并清 Cookie，必须重新登录。

## 5. 邀请与激活

```powershell
# 管理员邀请（owner/admin，且本次会话已完成 MFA）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" `
  -d '{"email":"new@demo.test","displayName":"新同事","personId":"<personId>","admin":false,"mfaRequired":false}' `
  "http://127.0.0.1:18080/api/v1/members/invitations"

# 接受邀请（公开端点，必须带 tenantId 到请求体）
curl.exe -X POST -H "Content-Type: application/json" `
  -d '{"tenantId":"<tenant>","activationToken":"<一次性令牌>","password":"N3w-Passw0rd!x"}' `
  "http://127.0.0.1:18080/api/v1/auth/invitations/accept"
```

- 首个成员自动成为 `owner` 并强制 MFA；
- 邀请令牌默认 72 小时（`PLATFORM_IDENTITY_INVITATION_TTL`），一次性使用；
- 库中只存哈希；`POST /members/{memberId}/reset-password` 同样返回一次性重置令牌；
- 重复邮箱返回 `409 EMAIL_ALREADY_EXISTS`；同一 Person 已有成员返回 `409 MEMBER_FOR_PERSON_EXISTS`。

## 6. 成员管理与账号更换

```powershell
# 列表（owner/admin，不强制 MFA）
curl.exe -H "X-Tenant-Id: <tenant>" -b cookies.txt "http://127.0.0.1:18080/api/v1/members"

# 停用成员：同时停用账号与主体、撤销全部会话、递增 auth_version
curl.exe -X PATCH -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" -d '{"status":"disabled"}' `
  "http://127.0.0.1:18080/api/v1/members/<memberId>"

# 更换登录账号
curl.exe -X PATCH -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" `
  -d '{"newUserId":"<userId>","reason":"员工转岗，旧账号停用"}' `
  "http://127.0.0.1:18080/api/v1/members/<memberId>/account"
```

- 不能停用自己：`409 CANNOT_DISABLE_SELF`；
- 新账号必须存在且未停用：`404 USER_NOT_FOUND` / `409 ACCOUNT_DISABLED`；
- 换号后新账号继承成员的 `mfaRequired`，旧账号置 `DISABLED`，历史表留痕。

## 7. 服务账号与 API 令牌

```powershell
# 创建服务账号（owner/admin + MFA）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" -d '{"name":"etl","description":"夜间同步"}' `
  "http://127.0.0.1:18080/api/v1/service-accounts"

# 签发令牌（1–90 天，明文只返回一次）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" -d '{"name":"prod","scopes":["read"],"ttlDays":30}' `
  "http://127.0.0.1:18080/api/v1/service-accounts/<id>/tokens"

# 用令牌访问业务接口（不需要 Cookie/CSRF）
curl.exe -H "X-Tenant-Id: <tenant>" -H "Authorization: Bearer <token>" `
  "http://127.0.0.1:18080/api/v1/system/jobs"
```

- 令牌前缀 `s_s_`，库中只存 SHA-256；列表接口不回显明文与哈希；
- 最长 90 天，超限 `422 TOKEN_TTL_INVALID`；
- 停用服务账号会撤销其全部令牌（`DELETE /service-accounts/{id}`）；
- 跨租户使用令牌会因 RLS 查不到行而返回 `401 TOKEN_INVALID`，不需要黑名单。

## 8. 自然人、无账号员工与再雇佣

```powershell
# 新建自然人 + 首段雇佣（不创建登录账号）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" `
  -d '{"displayName":"张三","primaryEmail":"zhang@demo.test","employmentNo":"E-1001","effectiveFrom":"2026-11-01","jobTitle":"工程师"}' `
  "http://127.0.0.1:18080/api/v1/people"

# 一人多雇佣 / 再雇佣：同一 personId 再追加一段
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" `
  -d '{"employmentNo":"E-2001","effectiveFrom":"2027-03-01","jobTitle":"兼职顾问"}' `
  "http://127.0.0.1:18080/api/v1/people/<personId>/employments"

# 结束一段雇佣
curl.exe -X PATCH -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" -d '{"effectiveTo":"2027-12-31"}' `
  "http://127.0.0.1:18080/api/v1/employments/<employmentId>/end"
```

- `employmentNo` 在租户内唯一：重复返回 `409`（唯一约束）；
- 无账号员工不写 `identity_user_account`，也不产生会话；
- 离职只结束 Employment，不自动删 Person；再入职就是新增 Employment。

## 9. 身份提供商（预留）

```powershell
# 只允许 draft / disabled
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -b cookies.txt -H "X-CSRF-Token: <csrf>" `
  -H "Content-Type: application/json" `
  -d '{"providerType":"oidc","name":"corp-sso","config":{"issuer":"https://sso.example.test"}}' `
  "http://127.0.0.1:18080/api/v1/identity-providers"
```

`providerType` 限 `oidc`/`saml`/`scim`；`status=active` 返回 `409 CONNECTOR_NOT_AVAILABLE`。
P0 只做模型与配置落库，不发起任何外部连接。

## 10. 错误码速查

| HTTP | 错误码 | 含义 |
|---|---|---|
| 401 | `TENANT_REQUIRED` | 未带 `X-Tenant-Id` |
| 401 | `SESSION_REQUIRED` | 没有 Cookie 也没有 Bearer |
| 401 | `INVALID_CREDENTIALS` | 邮箱或密码错误 |
| 401 | `MFA_TOKEN_INVALID` / `MFA_TOKEN_EXPIRED` / `MFA_TOKEN_CONSUMED` | MFA 票据不可用 |
| 401 | `MFA_CODE_INVALID` | TOTP / 恢复码错误 |
| 403 | `CSRF_REQUIRED` | 写操作缺少匹配的 `X-CSRF-Token` |
| 403 | `ACCOUNT_NOT_ACTIVE` | 账号未激活或已停用 |
| 403 | `MEMBER_REQUIRED` / `MEMBER_INACTIVE` / `MEMBER_NOT_ADMIN` | 成员或权限不足 |
| 403 | `MFA_REQUIRED` | 该操作要求本次会话已完成 MFA |
| 409 | `MFA_REQUIRED_FOR_PRIVILEGED` | 强制 MFA 成员不能关闭 MFA |
| 409 | `CONNECTOR_NOT_AVAILABLE` | P0 不允许把身份提供商置为 active |
| 422 | `PASSWORD_POLICY` / `PASSWORD_REUSED` | 不满足密码策略 / 复用历史密码 |
| 423 | `ACCOUNT_LOCKED` | 失败次数过多，临时锁定 |
| 429 | `MFA_ATTEMPTS_EXCEEDED` | 挑战尝试次数超限，重新登录 |

## 11. 排查

| 现象 | 先查 | 处置 |
|---|---|---|
| 所有业务请求 `401 SESSION_REQUIRED` | 请求是否带 Cookie 或 Bearer；`X-Subject-Id` 已无效 | 重新登录或改用令牌 |
| 浏览器写操作 `403 CSRF_REQUIRED` | `plt_csrf` 与 `X-CSRF-Token` 是否一致 | 前端统一从 Cookie 读值并带头 |
| 登录 `423 ACCOUNT_LOCKED` | `select failed_attempts, locked_until from identity_user_account where email = '<email>'` | 等锁定结束，或由管理员重置密码 |
| 会话突然失效（`SESSION_INVALID`） | `select auth_version, status from identity_user_account where id = '<userId>'` | 确认是否改密/重置/停用；重新登录 |
| MFA 端点 `503 MFA_UNAVAILABLE` | `PLATFORM_IDENTITY_MFA_KEY` 是否为 32 字节 base64 | 注入密钥后重启；不要用明文兜底 |
| 邀请 `409 EMAIL_ALREADY_EXISTS` | `select id, status from identity_user_account where email = '<email>'` | 改用重置密码或换邮箱 |
| 令牌 `401 TOKEN_INVALID` | 租户头是否与签发租户一致；`revoked_at`、`expires_at` | 跨租户必拒；撤销后重新签发 |
| 审计缺失 | `select event_type, success, user_id, actor_id, ip, occurred_at from identity_auth_event where tenant_id = '<tenant>' order by occurred_at desc` | 登录/MFA 成功失败都会写；缺失按事故处理 |

## 12. 数据库与迁移

- 迁移：`backend/db/src/main/resources/db/migration/V7__identity_and_session.sql`
- 组织域：`organization_person`、`organization_employment`
- 身份域：`identity_user_account`、`identity_actor`、`identity_tenant_member`、
  `identity_service_account`、`identity_member_account_history`、`identity_password_history`、
  `identity_invitation`、`identity_mfa_method`、`identity_recovery_code`、`identity_mfa_challenge`、
  `identity_session`、`identity_api_token`、`identity_identity_provider`、`identity_auth_event`
- 上述 16 张表全部 `ENABLE + FORCE ROW LEVEL SECURITY`，策略 `<table>_isolation`。
- `identity_session` 约束 `expires_at > created_at`；做过期演练时必须同时调整
  `created_at`、`idle_expires_at`、`expires_at`，否则违反约束。
- 校验：`powershell -File scripts/db-verify.ps1` 会跑 `70-identity.sql` 与 `IdentityDbTest`。

## 13. 已知边界

- 邮件发送、邀请链接落地页与密码找回邮件属 P1；当前令牌由接口返回、管理员转交。
- OIDC/SAML/SCIM 实际登录、SCIM 同步属 P1；本包只落模型与配置。
- 细粒度权限（谁能看哪个人、能否导出）属 WP02-04；当前 People 读接口只要求登录。
- 平台域运营身份与跨租户目录属 WP02-07。
- 账号合并、跨租户身份联合不在本包范围。
