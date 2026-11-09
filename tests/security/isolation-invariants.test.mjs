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
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));

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

test('隐私引擎仍强制排除社交软件', () => {
  const policy = readFileSync(
    join(repoRoot, 'desktop-agent', 'privacy-engine', 'WorkAssistant.PrivacyEngine', 'ForbiddenApplicationPolicy.cs'),
    'utf8',
  );
  for (const name of ['WeChat', 'Weixin', 'QQ', 'TIM']) {
    assert.match(policy, new RegExp(name, 'i'), `隐私引擎未排除 ${name}`);
  }
});
