/**
 * 端到端入口：默认跳过，仅在提供 PLATFORM_E2E_BASE_URL 时执行。
 *
 * CI 未配置目标环境时保持 skip，避免把「没跑」伪装成「通过」。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const baseUrl = process.env.PLATFORM_E2E_BASE_URL;
const skip = baseUrl ? false : '未提供 PLATFORM_E2E_BASE_URL';

test('GET /api/v1/system/health 返回 UP', { skip }, async () => {
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/system/health`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.status, 'UP');
  assert.equal(typeof body.service, 'string');
});
