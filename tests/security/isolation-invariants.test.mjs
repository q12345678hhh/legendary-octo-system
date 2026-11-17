/**
 * 安全不变量：隔离与零采集是产品红线，改坏必须让 CI 失败。
 *
 * 这些断言只看「默认值是否还安全」，行为验证在
 *   - desktop-agent/privacy-engine/WorkAssistant.PrivacyEngine.Checks（14 项断言）
 *   - plugin-runner/smoke.mjs（7 项容器冒烟）
 * 中执行。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const migrationDir = join(repoRoot, 'backend', 'db', 'src', 'main', 'resources', 'db', 'migration');
const bootApiDir = join(repoRoot, 'backend', 'boot-api', 'src', 'main', 'java', 'com', 'acme', 'platform');
const authorizationDir = join(repoRoot, 'backend', 'modules', 'authorization', 'src', 'main', 'java',
  'com', 'acme', 'platform', 'authorization');
const platformModuleDir = join(repoRoot, 'backend', 'modules', 'platform', 'src', 'main', 'java',
  'com', 'acme', 'platform', 'platform');
const worklogModuleDir = join(repoRoot, 'backend', 'modules', 'worklog', 'src', 'main', 'java',
  'com', 'acme', 'platform', 'worklog');
const workflowModuleDir = join(repoRoot, 'backend', 'modules', 'workflow', 'src', 'main', 'java',
  'com', 'acme', 'platform', 'workflow');

function javaFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return javaFiles(path);
    return entry.name.endsWith('.java') ? [path] : [];
  });
}

test('插件执行器镜像以非 root 用户运行且以监督进程作为 PID 1', () => {
  const dockerfile = readFileSync(join(repoRoot, 'plugin-runner', 'Dockerfile'), 'utf8');
  assert.match(dockerfile, /^USER plugin$/m, '镜像不得以 root 运行');
  assert.match(dockerfile, /ENTRYPOINT \["python3", "\/app\/runner\.py"\]/, 'PID 1 必须是监督进程');
  assert.doesNotMatch(dockerfile, /^USER root$/m);
});

test('插件执行器的资源限额默认值存在且不放开', () => {
  const dockerfile = readFileSync(join(repoRoot, 'plugin-runner', 'Dockerfile'), 'utf8');
  for (const key of ['PLUGIN_MEMORY_MB', 'PLUGIN_CPU_SECONDS', 'PLUGIN_OPEN_FILES', 'PLUGIN_PROCESSES']) {
    assert.match(dockerfile, new RegExp(`ENV[\\s\\S]*${key}=\\d+`), `缺少默认限额 ${key}`);
  }
});

test('含 tenant_id 的业务表必须同时 ENABLE 与 FORCE RLS', () => {
  const migrations = readdirSync(migrationDir)
    .filter((name) => name.endsWith('.sql'))
    .sort()
    .map((name) => readFileSync(join(migrationDir, name), 'utf8'))
    .join('\n');

  const tenantTables = [];
  const createTable = /CREATE TABLE (\w+)\s*\(([\s\S]*?)\n\);/g;
  for (const match of migrations.matchAll(createTable)) {
    const [, table, body] = match;
    if (table.startsWith('platform_')) continue; // 系统级跨租户队列，非租户业务表
    if (/\btenant_id\b/.test(body)) tenantTables.push(table);
  }
  assert.ok(tenantTables.length >= 50, `识别到的租户表过少：${tenantTables.length}`);

  for (const table of tenantTables) {
    assert.match(migrations, new RegExp(`ALTER TABLE ${table} ENABLE ROW LEVEL SECURITY`),
      `${table} 未启用 RLS`);
    assert.match(migrations, new RegExp(`ALTER TABLE ${table} FORCE ROW LEVEL SECURITY`),
      `${table} 未强制 RLS`);
  }
});

test('控制器与租户过滤器不得信任客户端主体头', () => {
  for (const file of javaFiles(bootApiDir)) {
    const source = readFileSync(file, 'utf8');
    if (!file.endsWith('Controller.java')) continue;
    assert.doesNotMatch(source, /getHeader\(\s*"X-(Subject|Actor)-Id"/,
      `${file} 直接读取了客户端主体头`);
  }
  const binding = readFileSync(join(bootApiDir, 'RequestContextBindingFilter.java'), 'utf8');
  assert.doesNotMatch(binding, /ActorContextHolder\.set/, '租户过滤器不得建立主体上下文');
  assert.doesNotMatch(binding, /getHeader\(\s*"X-(Subject|Actor)-Id"/,
    '租户过滤器不得接受客户端主体');
});

test('对象存储键与授权缓存键必须带租户前缀', () => {
  const fileService = readFileSync(
    join(platformModuleDir, 'file', 'FileService.java'), 'utf8');
  assert.match(fileService, /context\.tenantId\(\)\s*\+\s*"\/"\s*\+\s*fileId/,
    '文件对象键必须由 tenantId 打头');

  const cache = readFileSync(join(authorizationDir, 'AuthorizationCache.java'), 'utf8');
  assert.match(cache, /tenantId/, '授权缓存键必须包含 tenantId');
});

test('运行账号与迁移账号分离且不得绕过 RLS', () => {
  const yml = readFileSync(
    join(repoRoot, 'backend', 'boot-api', 'src', 'main', 'resources', 'application.yml'), 'utf8');
  assert.doesNotMatch(yml, /BYPASSRLS\s*[:=]\s*true/i, '运行账号不得开启 BYPASSRLS');
  assert.match(yml, /NOBYPASSRLS/i, '运行账号语义必须显式声明为 NOBYPASSRLS');
  assert.match(yml, /flyway:[\s\S]*user:/, '迁移账号必须与运行账号分开配置');
});

test('平台运营身份不得来自客户端请求头', () => {
  for (const file of javaFiles(bootApiDir)) {
    const source = readFileSync(file, 'utf8');
    if (file.endsWith('Controller.java')) {
      assert.doesNotMatch(source, /X-Platform-Operator-Id/,
        `${file} 仍在读取已退役的运营身份头`);
    }
  }
  const isolation = readFileSync(join(bootApiDir, 'PlatformDomainIsolationFilter.java'), 'utf8');
  assert.match(isolation, /PLATFORM_OPERATOR_HEADER_RETIRED/,
    '旧运营头必须被显式拒绝而不是继续作为身份');
  const platformAuth = readFileSync(
    join(bootApiDir, 'PlatformOperatorAuthenticationFilter.java'), 'utf8');
  assert.match(platformAuth, /getCookieName\(\)/, '平台身份必须来自独立会话 Cookie');
});

test('平台会话与租户会话的 Cookie 名和 Path 必须物理分离', () => {
  const yml = readFileSync(
    join(repoRoot, 'backend', 'boot-api', 'src', 'main', 'resources', 'application.yml'), 'utf8');
  assert.match(yml, /cookie-name:\s*\$\{PLATFORM_OPS_COOKIE:plt_ops_session\}/,
    '平台运营 Cookie 名必须是 plt_ops_session');
  assert.match(yml, /cookie-name:\s*\$\{PLATFORM_IDENTITY_COOKIE:plt_session\}/,
    '租户会话 Cookie 名必须是 plt_session');
  assert.match(yml, /cookie-path:\s*\/platform/, '平台 Cookie 必须只挂 Path=/platform');

  const cookies = readFileSync(join(bootApiDir, 'PlatformOperatorCookies.java'), 'utf8');
  assert.match(cookies, /SameSite=Strict/, '平台 Cookie 必须是 SameSite=Strict');
  assert.match(cookies, /HttpOnly/, '平台会话 Cookie 必须是 HttpOnly');
});

test('平台运营数据面不得查询租户业务正文', () => {
  const dirs = ['overview', 'directory', 'operations', 'catalog', 'settings', 'security'];
  const forbidden = /organization_person|organization_employment|worklog_|identity_user_account|platform_file/;
  for (const dir of dirs) {
    for (const file of javaFiles(join(platformModuleDir, dir))) {
      const source = readFileSync(file, 'utf8');
      assert.doesNotMatch(source, forbidden,
        `${file} 读取了租户业务表，平台运营端只能读元数据与聚合`);
    }
  }
});

test('回收站与数据主体请求只使用服务端租户上下文', () => {
  for (const name of ['RetentionController.java', 'PrivacyRequestController.java']) {
    const source = readFileSync(join(bootApiDir, name), 'utf8');
    assert.doesNotMatch(source, /getHeader\(/, `${name} 不得从请求头读取租户或主体`);
  }
  const privacy = readFileSync(join(bootApiDir, 'PrivacyRequestController.java'), 'utf8');
  assert.match(privacy, /RequestContextHolder\.current\(\)/,
    '数据主体请求必须绑定服务端租户上下文');

  const service = readFileSync(
    join(repoRoot, 'backend', 'modules', 'tenancy', 'src', 'main', 'java', 'com', 'acme', 'platform',
      'tenancy', 'retention', 'RetentionService.java'), 'utf8');
  assert.match(service, /transactions\.inTenant\(/, '回收站读写必须落在租户事务内');
  assert.doesNotMatch(service, /tenantId\s*=\s*[^;]*(?:Param|Header)/,
    '回收站服务不得从请求参数或请求头取租户');
});

test('平台侧删除判定与执行必须叠加 MFA 的管理权限', () => {
  const source = readFileSync(join(bootApiDir, 'PlatformRetentionController.java'), 'utf8');
  const writes = source.split(/@(?:Post|Put)Mapping/).slice(1);
  assert.ok(writes.length >= 5, `平台保留写接口过少：${writes.length}`);
  for (const block of writes) {
    const body = block.slice(0, block.indexOf('\n    }'));
    assert.match(body, /PlatformActors\.requireManage\(/,
      '平台保留策略与删除执行的写操作必须要求 manage 权限（叠加 MFA）');
  }
  assert.match(source, /SECURITY_MANAGE/, '删除判定/执行必须复用安全运营权限码');
});

test('保留事件与删除墓碑在数据库层只允许追加', () => {
  const sql = readFileSync(join(migrationDir, 'V12__retention_and_dsr.sql'), 'utf8');
  for (const table of ['retention_event', 'platform_deletion_tombstone']) {
    assert.match(sql,
      new RegExp(`CREATE TRIGGER ${table}_immutable_trigger\\s+BEFORE UPDATE OR DELETE ON ${table}`),
      `${table} 缺少不可变触发器`);
  }
  for (const table of ['retention_bin_item', 'retention_legal_hold', 'retention_event']) {
    assert.match(sql, new RegExp(`ALTER TABLE ${table} FORCE ROW LEVEL SECURITY`),
      `${table} 未强制 RLS`);
  }
});

test('恢复回收站条目不得重建授权或身份', () => {
  const targets = [
    join(platformModuleDir, 'file', 'FileRetentionTarget.java'),
    join(worklogModuleDir, 'device', 'SegmentRetentionTarget.java'),
  ];
  for (const file of targets) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /INSERT INTO authorization_/i, `${file} 在恢复流程里重建了授权`);
    assert.doesNotMatch(source, /INSERT INTO identity_/i, `${file} 在恢复流程里重建了身份`);
  }
  const fileTarget = readFileSync(targets[0], 'utf8');
  assert.match(fileTarget, /UPDATE platform_file SET status = 'available'/,
    '文件恢复只允许还原状态位');
});

test('工作流状态日志只追加、发布不可变且不直读他模块表', () => {
  const sql = readFileSync(join(migrationDir, 'V17__workflow_approval.sql'), 'utf8');
  assert.match(sql,
    /CREATE TRIGGER workflow_transition_log_immutable_trigger\s+BEFORE UPDATE OR DELETE ON workflow_transition_log/,
    '状态流转日志必须只追加');
  assert.match(sql,
    /CREATE TRIGGER workflow_definition_published_immutable\s+BEFORE UPDATE ON workflow_definition/,
    '发布后的工作流定义必须冻结');
  assert.match(sql,
    /CREATE UNIQUE INDEX workflow_definition_one_published[\s\S]*?WHERE status = 'published'/,
    '同一 key 只允许一个 published 版本');
  assert.match(sql, /ALTER TABLE workflow_task FORCE ROW LEVEL SECURITY/,
    '审批任务必须强制 RLS');

  const forbidden = /\b(?:worklog_|collab_|organization_|identity_|authorization_assignment)\w*/;
  for (const file of javaFiles(workflowModuleDir)) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, forbidden,
      `${file} 直接读取了其他模块的表，必须走公开只读查询`);
  }
});

test('隐私引擎仍强制排除社交软件', () => {
  const policy = readFileSync(
    join(repoRoot, 'desktop-agent', 'privacy-engine', 'WorkAssistant.PrivacyEngine', 'ForbiddenApplicationPolicy.cs'),
    'utf8',
  );
  for (const name of ['WeChat', 'Weixin', 'QQ', 'TIM']) {
    assert.match(policy, new RegExp(name, 'i'), `隐私引擎未排除 ${name}`);
  }
});
