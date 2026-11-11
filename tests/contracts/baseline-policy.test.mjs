/**
 * 强制排除基线一致性：契约、后端资源、客户端硬编码下限必须是同一份清单。
 *
 * 客户端故意保留一份硬编码清单：断网或策略未下发时也要能失败即拒绝。
 * 因此只要求「契约 ⊆ 客户端」，但后端资源必须与契约逐字一致。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const contractPath = join(repoRoot, 'contracts', 'work-assistant', 'baseline-policy.v1.json');
const backendPath = join(repoRoot, 'backend', 'modules', 'worklog', 'src', 'main', 'resources',
  'work-assistant', 'baseline-policy.v1.json');
const clientPath = join(repoRoot, 'desktop-agent', 'privacy-engine', 'WorkAssistant.PrivacyEngine',
  'ForbiddenApplicationPolicy.cs');

const contract = JSON.parse(readFileSync(contractPath, 'utf8'));
const backend = JSON.parse(readFileSync(backendPath, 'utf8'));
const client = readFileSync(clientPath, 'utf8');

function csStrings(blockName) {
  const start = client.indexOf(blockName);
  assert.ok(start >= 0, `客户端缺少 ${blockName}`);
  const end = client.indexOf('};', start);
  assert.ok(end > start, `${blockName} 未正确结束`);
  return [...client.slice(start, end).matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

test('后端资源与契约逐字一致', () => {
  assert.deepEqual(backend, contract, '后端基线资源与 contracts 不一致，请同步');
});

test('契约中的进程排除项必须全部存在于客户端硬编码下限', () => {
  const clientProcesses = csStrings('DefaultForbiddenProcesses');
  for (const processName of contract.processes) {
    assert.ok(clientProcesses.includes(processName),
      `客户端硬编码下限缺少 ${processName}，离线时会漏排除`);
  }
});

test('契约中的标题关键字必须全部存在于客户端硬编码下限', () => {
  const clientKeywords = csStrings('DefaultForbiddenTitleKeywords');
  for (const keyword of contract.title_keywords) {
    assert.ok(clientKeywords.includes(keyword),
      `客户端标题关键字缺少 ${keyword}`);
  }
});

test('基线不允许为空，且不得包含通配符', () => {
  assert.ok(contract.processes.length > 0);
  assert.ok(contract.title_keywords.length > 0);
  for (const processName of contract.processes) {
    assert.match(processName, /^[A-Za-z0-9._-]{1,64}$/, `基线进程名不合法: ${processName}`);
    assert.ok(!['*', '.*'].includes(processName), '基线不得包含通配符');
  }
});
