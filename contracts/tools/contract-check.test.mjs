#!/usr/bin/env node
/**
 * 契约门禁自测：证明三类契约的未批准破坏性变更都会阻断，且兼容变更不会误报。
 * 用法：node --test tools/contract-check.test.mjs
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const contractsRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
const checker = join(contractsRoot, 'tools', 'contract-check.mjs');
const sandboxes = [];

function sandbox() {
  const dir = mkdtempSync(join(tmpdir(), 'platform-contracts-'));
  for (const name of ['openapi', 'events', 'plugins', 'baseline']) {
    cpSync(join(contractsRoot, name), join(dir, name), { recursive: true });
  }
  sandboxes.push(dir);
  return dir;
}

function run(root) {
  return spawnSync(process.execPath, [checker, '--root', root], { encoding: 'utf8' });
}

function edit(root, relative, mutate) {
  const file = join(root, relative);
  const document = JSON.parse(readFileSync(file, 'utf8'));
  mutate(document);
  writeFileSync(file, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
}

function approve(root, relative, approval) {
  const dir = join(root, 'baseline', 'approvals');
  mkdirSync(dir, { recursive: true });
  const name = relative.replace(/[\\/]/g, '__').replace(/\.json$/, '');
  writeFileSync(join(dir, `${name}.json`), `${JSON.stringify(approval, null, 2)}\n`, 'utf8');
}

after(() => {
  for (const dir of sandboxes) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('contract-check', () => {
  it('通过当契约与基线一致', () => {
    const result = run(sandbox());

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /3 类覆盖完整/);
  });

  it('阻断 OpenAPI 删除必填字段', () => {
    const root = sandbox();
    edit(root, 'openapi/platform-api.v1.json', (document) => {
      delete document.components.schemas.SystemHealth.properties.service;
    });

    const result = run(root);

    assert.equal(result.status, 1, '删除字段必须阻断');
    assert.match(result.stderr, /schema:SystemHealth\.service 被删除/);
  });

  it('放行 OpenAPI 新增可选字段', () => {
    const root = sandbox();
    edit(root, 'openapi/platform-api.v1.json', (document) => {
      document.components.schemas.SystemHealth.properties.version = { type: 'string' };
    });

    const result = run(root);

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /向后兼容新增/);
  });

  it('阻断领域事件字段类型变更', () => {
    const root = sandbox();
    edit(root, 'events/employee.hired.v1.json', (document) => {
      document.allOf[1].properties.data.properties.hired_on.type = 'integer';
    });

    const result = run(root);

    assert.equal(result.status, 1, '类型变更必须阻断');
    assert.match(result.stderr, /event\.data\.hired_on 类型 string → integer/);
  });

  it('阻断插件协议删除限额字段', () => {
    const root = sandbox();
    edit(root, 'plugins/plugin-manifest.v1.json', (document) => {
      delete document.properties.limits.properties.network;
    });

    const result = run(root);

    assert.equal(result.status, 1, '插件协议变更必须阻断');
    assert.match(result.stderr, /manifest\.limits\.network 被删除/);
  });

  it('阻断事件目录与 schema 不同步', () => {
    const root = sandbox();
    edit(root, 'events/employee.hired.v1.json', (document) => {
      document['x-event-name'] = 'employee.onboarded.v1';
    });

    const result = run(root);

    assert.equal(result.status, 1);
    assert.match(result.stderr, /未登记到目录|事件名必须/);
  });

  it('缺任一类契约直接失败', () => {
    const root = sandbox();
    rmSync(join(root, 'plugins', 'plugin-manifest.v1.json'));

    const result = run(root);

    assert.equal(result.status, 1);
    assert.match(result.stderr, /未覆盖契约类别: 插件协议/);
  });

  it('已批准且未到期的破坏性变更放行并给出告警', () => {
    const root = sandbox();
    edit(root, 'openapi/platform-api.v1.json', (document) => {
      delete document.paths['/api/v1/system/health'].get.responses['503'];
    });
    approve(root, 'openapi/platform-api.v1.json', {
      changeRequest: 'CR-0001',
      expiresOn: '2099-12-31',
      reason: '自测用例：503 响应改由网关统一返回',
    });

    const result = run(root);

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /WARN .*CR-0001/);
  });

  it('过期的审批不能放行破坏性变更', () => {
    const root = sandbox();
    edit(root, 'openapi/platform-api.v1.json', (document) => {
      delete document.paths['/api/v1/system/health'].get.responses['503'];
    });
    approve(root, 'openapi/platform-api.v1.json', {
      changeRequest: 'CR-0002',
      expiresOn: '2020-01-01',
      reason: '自测用例：已过期',
    });

    const result = run(root);

    assert.equal(result.status, 1);
    assert.match(result.stderr, /到期/);
  });
});
