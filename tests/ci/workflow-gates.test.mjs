/**
 * CI 门禁接线断言：门禁必须真的挂在流水线上，且不允许被静默降级。
 *
 * 这些断言防的是「规范写在文档里、流水线里没跑」这类假完成。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const workflow = readFileSync(join(repoRoot, '.github', 'workflows', 'ci.yml'), 'utf8');

test('六个构建任务全部存在', () => {
  for (const job of ['backend', 'frontend', 'desktop-agent', 'plugin-runner', 'contracts', 'tests']) {
    assert.match(workflow, new RegExp(`^\\s{2}${job}:`, 'm'), `缺少 CI 任务 ${job}`);
  }
});

test('三类契约破坏性变更检测挂在 contracts 任务上', () => {
  const contractsJob = workflow.slice(workflow.indexOf('  contracts:'), workflow.indexOf('  tests:'));
  assert.match(contractsJob, /contract-check\.mjs/, 'contracts 任务未执行契约门禁');
  assert.match(contractsJob, /--test/, 'contracts 任务未执行门禁自测');
});

test('秘密扫描为阻断项', () => {
  assert.match(workflow, /gitleaks/, '未接入秘密扫描');
  const secretJob = workflow.slice(workflow.indexOf('  secret-scan:'), workflow.indexOf('  commit-lint:'));
  assert.doesNotMatch(secretJob, /continue-on-error:\s*true/, '秘密扫描不得允许失败继续');
});

test('容器与依赖漏洞扫描以非零退出码阻断', () => {
  assert.match(workflow, /severity:\s*CRITICAL,HIGH/, '漏洞扫描未锁定严重级别');
  assert.match(workflow, /exit-code:\s*'1'/, '漏洞扫描未设置为阻断');
  assert.match(workflow, /pnpm audit --audit-level high/, '未对前端依赖做漏洞审查');
  assert.match(workflow, /--vulnerable/, '未对 .NET 依赖做漏洞审查');
  assert.match(workflow, /dependency-check-maven/, '未对 Maven 依赖做漏洞审查');
});

test('覆盖率非回归门禁与 SBOM 已接线', () => {
  assert.match(workflow, /coverage-gate\.mjs/, '未执行覆盖率非回归门禁');
  assert.match(workflow, /bom\.json/, '未归档后端 SBOM');
  assert.match(workflow, /sbom --sbom-format cyclonedx/, '未生成前端 SBOM');
  assert.match(workflow, /artifact-manifest\.mjs/, '未生成制品哈希清单');
});

test('CI 不缓存构建产物，缓存只作用于依赖', () => {
  assert.match(workflow, /cache:\s*maven/, '未使用 Maven 依赖缓存');
  assert.match(workflow, /cache:\s*pnpm/, '未使用 pnpm 依赖缓存');
  assert.doesNotMatch(workflow, /cache:\s*'?(dist|target)/, '构建产物不得进入缓存');
});

test('仓库治理文件齐备', () => {
  for (const file of ['.editorconfig', '.gitattributes', '.github/CODEOWNERS', 'commitlint.config.mjs']) {
    assert.ok(existsSync(join(repoRoot, file)), `缺少 ${file}`);
  }
});

test('契约与覆盖率门槛目录受代码所有者保护', () => {
  const owners = readFileSync(join(repoRoot, '.github', 'CODEOWNERS'), 'utf8');
  assert.match(owners, /^\/contracts\//m, 'contracts 未指定代码所有者');
  assert.match(owners, /^\/\.github\//m, 'CI 配置未指定代码所有者');
});
