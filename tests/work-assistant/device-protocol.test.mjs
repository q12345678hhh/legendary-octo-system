/**
 * 工作助手设备协议门禁：
 * 1. 注册/心跳/策略/撤销/上传/更新清单端点必须存在于 OpenAPI；
 * 2. 契约中不得出现任何「远程开启采集」端点；
 * 3. 策略信封与策略更新请求必须是封闭字段集合，不能藏控制位。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const api = JSON.parse(readFileSync(join(repoRoot, 'contracts', 'openapi', 'platform-api.v1.json'), 'utf8'));

const REQUIRED = [
  ['post', '/api/v1/work-assistant/devices/register'],
  ['post', '/api/v1/work-assistant/devices/{deviceId}/heartbeat'],
  ['get', '/api/v1/work-assistant/devices/{deviceId}/policy'],
  ['post', '/api/v1/work-assistant/devices/{deviceId}/revoke'],
  ['post', '/api/v1/work-assistant/segments'],
  ['get', '/api/v1/work-assistant/update-manifest'],
  ['put', '/api/v1/work-assistant/policies/current'],
];

test('设备链路端点全部在契约中', () => {
  for (const [method, path] of REQUIRED) {
    assert.ok(api.paths[path]?.[method], `缺少 ${method.toUpperCase()} ${path}`);
  }
});

test('不存在远程开启或控制采集的端点', () => {
  for (const path of Object.keys(api.paths)) {
    if (!path.startsWith('/api/v1/work-assistant')) {
      continue;
    }
    assert.doesNotMatch(path, /\/(start|capture|enable|resume)\b/i,
      `禁止出现远程启动/控制采集端点: ${path}`);
  }
});

test('策略信封是封闭字段集合，且没有采集控制位', () => {
  const policy = api.components.schemas.SignedPolicy;
  assert.equal(policy.additionalProperties, false, '策略信封必须拒绝未知字段');
  const forbidden = ['captureEnabled', 'capture_enabled', 'autoStart', 'auto_start', 'startCapture'];
  for (const field of forbidden) {
    assert.ok(!(field in policy.properties), `策略中不得出现 ${field}`);
  }
});

test('策略更新请求只允许追加排除项', () => {
  const update = api.components.schemas.PolicyAppendRequest;
  assert.equal(update.additionalProperties, false);
  assert.deepEqual(Object.keys(update.properties), ['additional_exclusions']);
  assert.equal(update.required.length, 1);
  assert.equal(update.required[0], 'additional_exclusions');
});

test('上传请求是封闭字段集合，没有补传开关', () => {
  const upload = api.components.schemas.SegmentUploadRequest;
  assert.equal(upload.additionalProperties, false);
  for (const field of ['force', 'backfill', 'overridePolicy']) {
    assert.ok(!(field in upload.properties), `上传请求不得包含 ${field}`);
  }
});
