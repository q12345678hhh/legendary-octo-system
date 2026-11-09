/**
 * 验收证据：故意对三类契约各引入一处破坏性变更，门禁必须阻断。
 *
 * 这里把 contracts/tools/contract-check.mjs 当作黑盒产物调用（不复制其判定逻辑），
 * 只断言「返回码非 0」与「错误信息指向具体契约文件」。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const contractsDir = join(repoRoot, 'contracts');
const gate = join(contractsDir, 'tools', 'contract-check.mjs');

function withCopy(mutate) {
  const root = mkdtempSync(join(tmpdir(), 'platform-contract-'));
  cpSync(contractsDir, root, { recursive: true });
  rmSync(join(root, 'baseline', 'approvals'), { recursive: true, force: true });
  mutate(root);
  const result = spawnSync(process.execPath, [gate, '--root', root], { encoding: 'utf8' });
  rmSync(root, { recursive: true, force: true });
  return { status: result.status, output: `${result.stdout ?? ''}${result.stderr ?? ''}` };
}

function onlyJson(dir) {
  return readdirSync(dir).filter((name) => name.endsWith('.json'));
}

test('未改动的契约副本可以通过门禁', () => {
  const result = withCopy(() => {});
  assert.equal(result.status, 0, result.output);
});

test('OpenAPI 删除 schema 字段被阻断', () => {
  const result = withCopy((root) => {
    const dir = join(root, 'openapi');
    const file = join(dir, onlyJson(dir)[0]);
    const doc = JSON.parse(readFileSync(file, 'utf8'));
    const schema = Object.values(doc.components.schemas)[0];
    const [field] = Object.keys(schema.properties);
    delete schema.properties[field];
    schema.required = (schema.required ?? []).filter((name) => name !== field);
    writeFileSync(file, JSON.stringify(doc, null, 2));
  });
  assert.notEqual(result.status, 0, '删除 OpenAPI 字段后门禁必须失败');
  assert.match(result.output, /被删除|破坏性变更/);
});

test('领域事件删除数据字段被阻断', () => {
  const result = withCopy((root) => {
    const dir = join(root, 'events');
    const name = onlyJson(dir).find(
      (file) => file !== 'event-catalog.json' && !file.startsWith('envelope.'),
    );
    const file = join(dir, name);
    const schema = JSON.parse(readFileSync(file, 'utf8'));
    const branch = (schema.allOf ?? []).find((item) => item.properties);
    const [field] = Object.keys(branch.properties);
    delete branch.properties[field];
    branch.required = (branch.required ?? []).filter((item) => item !== field);
    writeFileSync(file, JSON.stringify(schema, null, 2));
  });
  assert.notEqual(result.status, 0, '删除事件字段后门禁必须失败');
  assert.match(result.output, /被删除|破坏性变更/);
});

test('插件协议移除 protocol_version 被阻断', () => {
  const result = withCopy((root) => {
    const dir = join(root, 'plugins');
    const file = join(dir, onlyJson(dir)[0]);
    const schema = JSON.parse(readFileSync(file, 'utf8'));
    schema.required = schema.required.filter((name) => name !== 'protocol_version');
    writeFileSync(file, JSON.stringify(schema, null, 2));
  });
  assert.notEqual(result.status, 0, '插件协议缺少 protocol_version 必须失败');
});

test('缺失一整类契约被阻断（不允许先空着）', () => {
  const result = withCopy((root) => {
    rmSync(join(root, 'plugins'), { recursive: true, force: true });
  });
  assert.notEqual(result.status, 0, '缺少插件协议类别必须失败');
  assert.match(result.output, /未覆盖契约类别/);
});
