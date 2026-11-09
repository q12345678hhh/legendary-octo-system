#!/usr/bin/env node
/**
 * 覆盖率非回归门禁。
 *
 * 汇总 backend 下所有模块的 JaCoCo XML 报告，计算指令覆盖率，与
 * backend/coverage-scope.yaml 中的主分支基线比较；低于基线（超出容差）即失败。
 *
 * 用法：
 *   node backend/tools/coverage-gate.mjs                  // 校验
 *   node backend/tools/coverage-gate.mjs --write-baseline // 在允许的发布提交上更新基线
 */
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const backendDir = resolve(fileURLToPath(new URL('..', import.meta.url)));
const scopeFile = join(backendDir, 'coverage-scope.yaml');
const writeBaseline = process.argv.includes('--write-baseline');

function findJacocoXml(dir, found = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.git') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      findJacocoXml(full, found);
    } else if (entry === 'jacoco.xml') {
      found.push(full);
    }
  }
  return found;
}

const reports = findJacocoXml(backendDir);
if (reports.length === 0) {
  console.error('FAIL: 未找到任何 jacoco.xml，先执行 mvn verify 生成报告');
  process.exit(1);
}

let missed = 0;
let covered = 0;
const perModule = [];

for (const report of reports) {
  const xml = readFileSync(report, 'utf8');
  const counters = [...xml.matchAll(/<counter type="INSTRUCTION" missed="(\d+)" covered="(\d+)"\s*\/>/g)];
  if (counters.length === 0) continue;
  // 报告级计数器是文件中最后一个 INSTRUCTION 计数器（方法/类级计数器在前）。
  const last = counters[counters.length - 1];
  const m = Number(last[1]);
  const c = Number(last[2]);
  missed += m;
  covered += c;
  perModule.push({ file: report.replace(backendDir, 'backend'), missed: m, covered: c });
}

const total = missed + covered;
if (total === 0) {
  console.error('FAIL: 覆盖率为空，未采集到任何可执行指令');
  process.exit(1);
}

const ratio = covered / total;
const scope = readFileSync(scopeFile, 'utf8');
const baselineMatch = scope.match(/^baseline_instruction_ratio:\s*([0-9.]+)/m);
const toleranceMatch = scope.match(/^tolerance:\s*([0-9.]+)/m);
if (!baselineMatch) {
  console.error('FAIL: coverage-scope.yaml 缺少 baseline_instruction_ratio');
  process.exit(1);
}
const baseline = Number(baselineMatch[1]);
const tolerance = toleranceMatch ? Number(toleranceMatch[1]) : 0;

console.log(`报告数量: ${perModule.length}`);
for (const module of perModule) {
  console.log(`  ${module.file}  covered=${module.covered} missed=${module.missed}`);
}
console.log(`指令覆盖率: ${(ratio * 100).toFixed(2)}% (covered=${covered}, missed=${missed})`);
console.log(`基线: ${(baseline * 100).toFixed(2)}% (tolerance=${tolerance})`);

if (writeBaseline) {
  const next = ratio.toFixed(4);
  writeFileSync(scopeFile, scope.replace(/^baseline_instruction_ratio:\s*[0-9.]+$/m, `baseline_instruction_ratio: ${next}`), 'utf8');
  console.log(`基线已更新为 ${next}`);
  process.exit(0);
}

if (ratio + tolerance < baseline) {
  console.error(`FAIL: 覆盖率 ${(ratio * 100).toFixed(2)}% 低于基线 ${(baseline * 100).toFixed(2)}%`);
  process.exit(1);
}

console.log('PASS: 覆盖率满足非回归门槛');
