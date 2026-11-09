#!/usr/bin/env node
/**
 * 交换契约门禁：三类契约（OpenAPI、领域事件、插件协议）全覆盖 + 破坏性变更检测。
 *
 * 规则见 docs/api/contract-governance.md：
 *   - 缺任一类契约直接失败，不允许「先空着」
 *   - 删除字段/端点、修改类型、新增必填、收窄枚举、修改信封字段 = 破坏性变更
 *   - 破坏性变更必须带已批准的变更请求（contracts/baseline/approvals/*.json）且未到期
 *
 * 用法：
 *   node tools/contract-check.mjs                 // 校验（CI 使用）
 *   node tools/contract-check.mjs --update        // 重新冻结基线（评审后执行）
 *   node tools/contract-check.mjs --root <dir>    // 指定契约根目录（自测使用）
 */
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const defaultRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
const rootFlag = process.argv.indexOf('--root');
const root = rootFlag > -1 ? resolve(process.argv[rootFlag + 1]) : defaultRoot;
const update = process.argv.includes('--update');
const baselinePath = join(root, 'baseline', 'contracts-baseline.json');

const ENVELOPE_FIELDS = [
  'event_id',
  'event_name',
  'occurred_at',
  'tenant_id',
  'correlation_id',
  'producer',
  'schema_version',
];

const STRUCTURAL = [];

function structural(condition, message) {
  if (!condition) {
    STRUCTURAL.push(message);
  }
}

function readJson(file) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

function listJson(dir) {
  try {
    return readdirSync(dir).filter((name) => name.endsWith('.json')).sort();
  } catch {
    return [];
  }
}

/** 递归收集 JSON Schema 属性签名：类型、是否必填、枚举取值。 */
function collectSchemaTokens(prefix, schema, tokens) {
  const required = new Set(Array.isArray(schema.required) ? schema.required : []);
  const properties = schema.properties && typeof schema.properties === 'object' ? schema.properties : {};

  for (const [name, property] of Object.entries(properties)) {
    const id = `${prefix}.${name}`;
    const type = typeof property.type === 'string' ? property.type : (property.$ref ? '$ref' : 'object');
    const token = { type, required: required.has(name) };
    if (Array.isArray(property.enum)) {
      token.enum = property.enum.map(String).sort();
    }
    tokens[id] = token;
    if (property.properties) {
      collectSchemaTokens(id, property, tokens);
    }
  }
}

function extractOpenApi(document) {
  const tokens = {};
  const paths = document.paths && typeof document.paths === 'object' ? document.paths : {};

  for (const [path, item] of Object.entries(paths)) {
    for (const [method, operation] of Object.entries(item)) {
      if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) {
        continue;
      }
      const base = `${method.toUpperCase()} ${path}`;
      tokens[`operation:${base}`] = {
        requestBodyRequired: Boolean(operation.requestBody?.required),
      };
      for (const status of Object.keys(operation.responses ?? {})) {
        tokens[`response:${base}:${status}`] = { defined: true };
      }
      const parameters = [...(item.parameters ?? []), ...(operation.parameters ?? [])];
      for (const parameter of parameters) {
        if (!parameter?.name || !parameter?.in) {
          continue;
        }
        tokens[`parameter:${base}:${parameter.in}:${parameter.name}`] = {
          required: Boolean(parameter.required),
          type: parameter.schema?.type ?? 'string',
        };
      }
    }
  }

  for (const [name, schema] of Object.entries(document.components?.schemas ?? {})) {
    collectSchemaTokens(`schema:${name}`, schema, tokens);
  }
  return tokens;
}

function extractEvent(schema) {
  const tokens = {};
  const branches = Array.isArray(schema.allOf) ? schema.allOf : [schema];
  for (const branch of branches) {
    if (branch.$ref) {
      continue;
    }
    collectSchemaTokens('event', branch, tokens);
  }
  if (schema.properties) {
    collectSchemaTokens('event', schema, tokens);
  }
  return tokens;
}

function extractPlugin(schema) {
  const tokens = {};
  collectSchemaTokens('manifest', schema, tokens);
  return tokens;
}

function contractEntries() {
  const entries = [];

  for (const name of listJson(join(root, 'openapi'))) {
    const file = join(root, 'openapi', name);
    entries.push({ kind: 'openapi', relative: `openapi/${name}`, file, tokens: extractOpenApi(readJson(file)) });
  }

  const eventFiles = listJson(join(root, 'events')).filter(
    (name) => name !== 'event-catalog.json' && !name.startsWith('envelope.'),
  );
  for (const name of eventFiles) {
    const file = join(root, 'events', name);
    const schema = readJson(file);
    const eventName = schema['x-event-name'];

    structural(/\.v[0-9]+\.json$/.test(name), `事件契约文件名必须带主版本号: ${name}`);
    structural(
      typeof eventName === 'string' && /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*\.v[0-9]+$/.test(eventName),
      `事件名必须为「过去时小写点分名.vN」: ${name} -> ${eventName}`,
    );
    structural(
      typeof eventName === 'string' && !/[a-z]ing\.v[0-9]+$/.test(eventName),
      `事件名不得使用进行时: ${eventName}`,
    );
    structural(
      JSON.stringify(schema).includes('envelope.v'),
      `事件契约必须引用固定信封: ${name}`,
    );

    const allOfRefs = (schema.allOf ?? []).map((branch) => branch.$ref ?? '');
    structural(allOfRefs.some((ref) => ref.includes('envelope.v')), `事件契约必须通过 allOf 引用信封: ${name}`);

    entries.push({ kind: 'events', relative: `events/${name}`, file, tokens: extractEvent(schema) });
  }

  for (const name of listJson(join(root, 'plugins'))) {
    const file = join(root, 'plugins', name);
    const schema = readJson(file);
    structural(
      Array.isArray(schema.required) && schema.required.includes('protocol_version'),
      `插件契约必须要求 protocol_version: ${name}`,
    );
    entries.push({ kind: 'plugins', relative: `plugins/${name}`, file, tokens: extractPlugin(schema) });
  }

  const catalogFile = join(root, 'events', 'event-catalog.json');
  if (existsSync(catalogFile)) {
    const catalog = readJson(catalogFile);
    const tokens = {};
    for (const event of catalog.events ?? []) {
      tokens[`catalog:${event.name}`] = { version: event.version, schema: event.schema };
    }
    entries.push({
      kind: 'events-catalog',
      relative: 'events/event-catalog.json',
      file: catalogFile,
      tokens,
    });
    checkCatalogConsistency(catalog, eventFiles);
  }

  return entries;
}

function checkCatalogConsistency(catalog, eventFiles) {
  const declared = new Map((catalog.events ?? []).map((event) => [event.name, event]));
  const files = new Set(eventFiles);

  for (const [name, event] of declared) {
    structural(files.has(event.schema), `事件目录登记的 schema 不存在: ${name} -> ${event.schema}`);
  }

  for (const file of eventFiles) {
    const schema = readJson(join(root, 'events', file));
    const eventName = schema['x-event-name'];
    structural(declared.has(eventName), `事件未登记到目录: ${eventName ?? file}`);
    const entry = declared.get(eventName);
    if (entry) {
      structural(entry.schema === file, `目录 schema 与文件名不一致: ${eventName} (${entry.schema} != ${file})`);
      structural(entry.version === schema['x-event-version'], `目录版本与 x-event-version 不一致: ${eventName}`);
    }
  }
}

function checkEnvelope() {
  const file = join(root, 'events', 'envelope.v1.json');
  if (!existsSync(file)) {
    STRUCTURAL.push('缺少领域事件信封: events/envelope.v1.json');
    return;
  }
  const envelope = readJson(file);
  const required = new Set(envelope.required ?? []);
  for (const field of ENVELOPE_FIELDS) {
    structural(required.has(field), `信封缺少固定字段或未设为必填: ${field}`);
  }
  structural(
    (envelope.required ?? []).length === ENVELOPE_FIELDS.length,
    '信封字段固定为 7 个，不得增删',
  );
}

function diffTokens(previous, next) {
  const breaking = [];
  const compatible = [];

  for (const [id, before] of Object.entries(previous)) {
    const after = next[id];
    if (!after) {
      breaking.push(`${id} 被删除`);
      continue;
    }
    if ('type' in before && before.type !== after.type) {
      breaking.push(`${id} 类型 ${before.type} → ${after.type}`);
    }
    if (typeof before.required === 'boolean' && !before.required && after.required) {
      breaking.push(`${id} 由可选改为必填`);
    }
    if (before.enum && after.enum) {
      const removed = before.enum.filter((value) => !after.enum.includes(value));
      if (removed.length > 0) {
        breaking.push(`${id} 枚举收窄，移除 ${removed.join(', ')}`);
      }
    }
    if (before.requestBodyRequired === false && after.requestBodyRequired === true) {
      breaking.push(`${id} 请求体改为必填`);
    }
    if ('version' in before && before.version !== after.version) {
      breaking.push(`${id} 主版本变化 ${before.version} → ${after.version}`);
    }
    if ('schema' in before && before.schema !== after.schema) {
      breaking.push(`${id} schema 指向变化 ${before.schema} → ${after.schema}`);
    }
  }

  for (const [id, after] of Object.entries(next)) {
    if (previous[id]) {
      continue;
    }
    if (after.required === true) {
      breaking.push(`${id} 新增必填字段`);
    } else {
      compatible.push(`${id} 新增`);
    }
  }

  return { breaking, compatible };
}

function approvalFor(relativePath) {
  const name = relativePath.replace(/[\\/]/g, '__').replace(/\.json$/, '');
  const file = join(root, 'baseline', 'approvals', `${name}.json`);
  if (!existsSync(file)) {
    return null;
  }
  const approval = readJson(file);
  const expiry = Date.parse(approval.expiresOn ?? '');
  if (!approval.changeRequest || Number.isNaN(expiry)) {
    return { valid: false, reason: '审批文件缺少 changeRequest 或 expiresOn' };
  }
  if (expiry < Date.now()) {
    return { valid: false, reason: `审批已于 ${approval.expiresOn} 到期` };
  }
  return { valid: true, changeRequest: approval.changeRequest, expiresOn: approval.expiresOn };
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function main() {
  checkEnvelope();
  const entries = contractEntries();

  for (const [kind, label] of [
    ['openapi', 'OpenAPI'],
    ['events', '领域事件'],
    ['plugins', '插件协议'],
  ]) {
    structural(entries.some((entry) => entry.kind === kind), `未覆盖契约类别: ${label}`);
  }

  for (const message of STRUCTURAL) {
    console.error(`FAIL ${message}`);
  }
  if (STRUCTURAL.length > 0) {
    console.error(`\n结构性校验失败 ${STRUCTURAL.length} 项`);
    return 1;
  }

  const current = {};
  for (const entry of entries) {
    current[entry.relative] = {
      kind: entry.kind,
      sha256: sha256(entry.file),
      tokens: entry.tokens,
    };
  }

  if (update) {
    mkdirSync(dirname(baselinePath), { recursive: true });
    writeFileSync(
      baselinePath,
      `${JSON.stringify({ frozenAt: new Date().toISOString(), contracts: current }, null, 2)}\n`,
      'utf8',
    );
    console.log(`基线已更新: ${Object.keys(current).length} 份契约 -> ${baselinePath}`);
    return 0;
  }

  if (!existsSync(baselinePath)) {
    console.error(`FAIL 缺少契约基线: ${baselinePath}，先执行 node tools/contract-check.mjs --update`);
    return 1;
  }

  const baseline = readJson(baselinePath).contracts ?? {};
  const failures = [];
  const warnings = [];

  for (const relative of Object.keys(baseline)) {
    if (!current[relative]) {
      failures.push(`${relative} 契约文件被删除`);
    }
  }

  for (const [relative, contract] of Object.entries(current)) {
    const previous = baseline[relative];
    if (!previous) {
      console.log(`INFO 新增契约 ${relative}（无基线，按新增处理）`);
      continue;
    }

    const { breaking, compatible } = diffTokens(previous.tokens, contract.tokens);
    if (compatible.length > 0) {
      console.log(`INFO ${relative} 向后兼容新增 ${compatible.length} 项`);
    }
    if (breaking.length === 0) {
      console.log(`PASS ${relative}`);
      continue;
    }

    const approval = approvalFor(relative);
    if (approval?.valid) {
      warnings.push(`${relative} 破坏性变更已批准（${approval.changeRequest}，到期 ${approval.expiresOn}）`);
      continue;
    }

    const suffix = approval ? `：${approval.reason}` : '：缺少审批文件';
    failures.push(`${relative} 存在未批准破坏性变更（${breaking.join('；')}）${suffix}`);
  }

  for (const message of warnings) {
    console.warn(`WARN ${message}`);
  }
  for (const message of failures) {
    console.error(`FAIL ${message}`);
  }

  if (failures.length > 0) {
    console.error(`\n契约门禁失败 ${failures.length} 项`);
    return 1;
  }

  console.log(`\n契约门禁通过：${entries.length} 份契约，3 类覆盖完整`);
  return 0;
}

process.exit(main());
