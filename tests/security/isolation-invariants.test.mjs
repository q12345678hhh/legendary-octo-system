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

test('隐私引擎仍强制排除社交软件', () => {
  const policy = readFileSync(
    join(repoRoot, 'desktop-agent', 'privacy-engine', 'WorkAssistant.PrivacyEngine', 'ForbiddenApplicationPolicy.cs'),
    'utf8',
  );
  for (const name of ['WeChat', 'Weixin', 'QQ', 'TIM']) {
    assert.match(policy, new RegExp(name, 'i'), `隐私引擎未排除 ${name}`);
  }
});
