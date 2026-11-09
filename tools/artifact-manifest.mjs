#!/usr/bin/env node
/**
 * 制品清单：为每个候选版生成不可变版本号、制品哈希与依赖清单索引。
 *
 * 用法：
 *   node tools/artifact-manifest.mjs --input <制品目录> --output release-manifest.json
 *
 * 版本规则：`<tag 或 0.0.0>-<7 位提交号>`。同一提交重复生成得到相同清单（不含时间戳、不含绝对路径），
 * 便于比对两次干净构建是否一致。
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index > -1 ? process.argv[index + 1] : fallback;
}

function git(args, fallback) {
  try {
    return execFileSync('git', args, { encoding: 'utf8' }).trim();
  } catch {
    return fallback;
  }
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function walk(dir, found = []) {
  for (const entry of readdirSync(dir).sort()) {
    const full = join(dir, entry);
    const info = statSync(full);
    if (info.isDirectory()) {
      walk(full, found);
    } else {
      found.push({ path: full, size: info.size });
    }
  }
  return found;
}

const inputDir = resolve(arg('input', '.'));
const outputFile = resolve(arg('output', 'release-manifest.json'));

const commit = process.env.GITHUB_SHA ?? git(['rev-parse', 'HEAD'], 'unknown');
const refName = process.env.GITHUB_REF_NAME ?? git(['describe', '--tags', '--always'], '0.0.0');
const tag = /^v?\d+\.\d+\.\d+/.test(refName) ? refName.replace(/^v/, '') : '0.0.0';
const version = `${tag}-${commit.slice(0, 7)}`;

const files = walk(inputDir).map((file) => ({
  path: relative(inputDir, file.path).split(sep).join('/'),
  size: file.size,
  sha256: sha256(file.path),
}));

if (files.length === 0) {
  console.error(`FAIL: ${inputDir} 下没有任何制品，无法生成清单`);
  process.exit(1);
}

const manifests = files
  .filter((file) => /(^|\/)(bom\.json|bom\.cdx\.json|repository\.cdx\.json|[^/]+\.cdx\.json)$/.test(file.path))
  .map((file) => file.path);

const manifest = {
  version,
  commit,
  immutable: true,
  dependencyManifests: manifests,
  artifacts: files,
};

writeFileSync(outputFile, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`版本 ${version}：${files.length} 个制品，${manifests.length} 份依赖清单 -> ${outputFile}`);
