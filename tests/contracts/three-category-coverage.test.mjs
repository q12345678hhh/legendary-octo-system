/**
 * 契约三类覆盖断言。
 *
 * 与 contracts/tools/contract-check.mjs 的分工：
 *   - contract-check 负责「实现正确性」（差分、审批、目录一致性）；
 *   - 本测试从消费者视角断言「覆盖存在性」与「结构最小完备」，
 *     避免门禁实现有 bug 时把缺失当成通过。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const contractsDir = join(repoRoot, 'contracts');

const categories = [
  { dir: 'openapi', label: 'OpenAPI' },
  { dir: 'events', label: '领域事件' },
  { dir: 'plugins', label: '插件协议' },
];

function jsonFiles(dir) {
  const full = join(contractsDir, dir);
  if (!existsSync(full)) {
    return [];
  }
  return readdirSync(full).filter((name) => name.endsWith('.json'));
}

test('三类契约目录均存在且每类至少一份契约', () => {
  for (const { dir, label } of categories) {
    const files = jsonFiles(dir);
    assert.ok(files.length > 0, `${label}（contracts/${dir}/）没有任何契约文件`);
  }
});

test('OpenAPI 契约声明 3.1 且同时定义 paths 与 components.schemas', () => {
  const files = jsonFiles('openapi');
  assert.ok(files.length > 0);
  for (const name of files) {
    const doc = JSON.parse(readFileSync(join(contractsDir, 'openapi', name), 'utf8'));
    assert.match(doc.openapi ?? '', /^3\.1\./, `${name} 必须为 OpenAPI 3.1`);
    assert.ok(Object.keys(doc.paths ?? {}).length > 0, `${name} 没有定义任何端点`);
    assert.ok(Object.keys(doc.components?.schemas ?? {}).length > 0, `${name} 没有定义任何 schema`);
  }
});

test('每个领域事件契约都以 .vN 结尾并引用固定信封', () => {
  const files = jsonFiles('events').filter(
    (name) => name !== 'event-catalog.json' && !name.startsWith('envelope.'),
  );
  assert.ok(files.length > 0, '没有登记任何领域事件契约');
  for (const name of files) {
    assert.match(name, /\.v[0-9]+\.json$/, `${name} 必须带主版本号`);
    assert.ok(
      JSON.stringify(JSON.parse(readFileSync(join(contractsDir, 'events', name), 'utf8'))).includes('envelope.v'),
      `${name} 未引用信封`,
    );
  }
});

test('信封固定 7 个必填字段', () => {
  const envelope = JSON.parse(readFileSync(join(contractsDir, 'events', 'envelope.v1.json'), 'utf8'));
  assert.deepEqual([...(envelope.required ?? [])].sort(), [
    'correlation_id',
    'event_id',
    'event_name',
    'occurred_at',
    'producer',
    'schema_version',
    'tenant_id',
  ]);
});

test('插件清单要求 protocol_version 且声明无网络限额', () => {
  const files = jsonFiles('plugins');
  assert.ok(files.length > 0);
  for (const name of files) {
    const manifest = JSON.parse(readFileSync(join(contractsDir, 'plugins', name), 'utf8'));
    assert.ok((manifest.required ?? []).includes('protocol_version'), `${name} 未要求 protocol_version`);
    assert.deepEqual(
      manifest.properties?.limits?.properties?.network?.enum,
      ['none'],
      `${name} 未锁定无网络`,
    );
  }
});
