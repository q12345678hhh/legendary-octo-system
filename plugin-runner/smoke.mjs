#!/usr/bin/env node
/**
 * 插件执行器冒烟：验证监督进程是 PID 1、退出码按信号语义透传、资源与网络隔离生效。
 * 对应 ADR-0005 与 docs/spikes/spike-D-plugin-isolation.md 第 7 节。
 *
 * 用法：node plugin-runner/smoke.mjs
 */
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const image = 'platform/plugin-runner:local';

// 本机 Docker 引擎位于 WSL，Windows 侧 docker CLI 默认指向不存在的 Docker Desktop 管道。
// 通过 PLATFORM_DOCKER 指定前缀（例如 `wsl -d pglab -u root --exec docker`），CI 直接用 docker。
const dockerPrefix = (process.env.PLATFORM_DOCKER ?? 'docker').split(/\s+/).filter(Boolean);
const usingWsl = dockerPrefix.includes('wsl');

function toWslPath(windowsPath) {
  return windowsPath.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, drive) => `/mnt/${drive.toLowerCase()}`);
}

const contextPath = usingWsl ? toWslPath(here) : '.';
const dockerfilePath = usingWsl ? `${contextPath}/Dockerfile` : 'Dockerfile';

function docker(args, options = {}) {
  return spawnSync(dockerPrefix[0], [...dockerPrefix.slice(1), ...args], { encoding: 'utf8', ...options });
}

function runPlugin(pluginArgs, extraArgs = []) {
  return docker(['run', '--rm', '--network', 'none', ...extraArgs, image, '--', ...pluginArgs]);
}

const results = [];
function record(name, passed, detail) {
  results.push({ name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'} ${name}${detail ? ` -> ${detail}` : ''}`);
}

console.log(`构建镜像 ${image}（context=${contextPath}）`);
const build = docker(['build', '-t', image, '-f', dockerfilePath, contextPath]);
if (build.status !== 0) {
  console.error(build.stdout);
  console.error(build.stderr);
  console.error('FAIL 镜像构建失败');
  process.exit(1);
}

// 1. 正常退出透传 0
const ok = runPlugin(['/bin/sh', '-c', 'exit 0']);
record('正常退出透传 0', ok.status === 0, `exit=${ok.status}`);
record(
  '监督进程在容器内是 PID 1',
  (ok.stderr ?? '').includes('"event": "supervisor_start"') && (ok.stderr ?? '').includes('"is_pid1": true'),
  'stderr 记录 is_pid1=true',
);

// 2. 非零退出码透传
const seven = runPlugin(['/bin/sh', '-c', 'exit 7']);
record('非零退出码透传 7', seven.status === 7, `exit=${seven.status}`);

// 3. 崩溃不被伪装成成功：SIGSEGV → 139
const crash = runPlugin(['/bin/sh', '-c', 'kill -SEGV $$']);
record('SIGSEGV 映射为 139 而非 0', crash.status === 139, `exit=${crash.status}`);

// 4. 插件不是 PID 1
const pidProbe = runPlugin(['/bin/sh', '-c', 'echo CHILD_PID=$$']);
const childPid = /CHILD_PID=(\d+)/.exec(pidProbe.stdout ?? '')?.[1];
record('插件子进程不是 PID 1', childPid !== undefined && childPid !== '1', `child_pid=${childPid}`);

// 5. 网络隔离：--network none 下无法建立外部连接
const network = runPlugin([
  'python3',
  '-c',
  'import socket; socket.create_connection(("1.1.1.1", 80), 2)',
]);
record('无网络时外部连接失败', network.status !== 0, `exit=${network.status}`);

// 6. 内存限额：超出 RLIMIT_AS 时进程失败而非拖垮宿主
const memory = runPlugin(
  ['python3', '-c', 'block = bytearray(256 * 1024 * 1024); print(len(block))'],
  ['--memory-mb', '96'],
);
record('超出内存限额时失败', memory.status !== 0, `exit=${memory.status}`);

const failed = results.filter((item) => !item.passed);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length > 0) {
  console.error(`失败项: ${failed.map((item) => item.name).join(', ')}`);
  process.exit(1);
}
