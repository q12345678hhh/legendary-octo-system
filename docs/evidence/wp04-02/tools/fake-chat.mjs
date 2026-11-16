// 本地假 chat completions 服务：只用于 WP04-02 验收取证，不参与产品运行。
//
// 用法：
//   node docs/evidence/wp04-02/tools/fake-chat.mjs          # 端口 18099，clean 模式
//   curl http://127.0.0.1:18099/__mode?value=dirty           # 切换为含编造数字/非法 entryId 的响应
//   curl http://127.0.0.1:18099/__mode                       # 查看当前模式
//
// clean：只使用请求事实包里的 totalMinutes / confirmedDays / 日期与真实 entryId。
// dirty：额外返回编造数字与不存在的 entryId，用于验证服务端校验器会丢弃不可回溯内容。

import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 18099);
let mode = process.env.FAKE_MODE === 'dirty' ? 'dirty' : 'clean';

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function factsFrom(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const user = messages.find((item) => item?.role === 'user');
  const content = typeof user?.content === 'string' ? user.content : '';
  const start = content.indexOf('{');
  const end = content.lastIndexOf('}');
  if (start < 0 || end <= start) {
    throw new Error('请求里没有事实 JSON');
  }
  return JSON.parse(content.slice(start, end + 1));
}

function cleanPayload(facts) {
  const entries = Array.isArray(facts.entries) ? facts.entries : [];
  const ids = entries.map((entry) => entry.entryId).filter((id) => typeof id === 'string');
  const dates = entries.map((entry) => entry.date).filter((date) => typeof date === 'string').sort();
  const firstIds = ids.slice(0, 3);
  const payload = {
    summaryPoints: [
      {
        text: `本周共确认 ${facts.confirmedDays} 天，累计 ${facts.totalMinutes} 分钟。`,
        entryIds: firstIds,
      },
      {
        text: `工时记录覆盖 ${dates[0]} 至 ${dates[dates.length - 1]} 的已确认快照。`,
        entryIds: firstIds,
      },
      {
        text: '工作集中在已登记的项目与任务上，条目均可回溯到当日快照。',
        entryIds: firstIds,
      },
    ],
    highlights: [
      { text: '本周工时记录完整，可逐条回溯到确认快照。', entryIds: firstIds.slice(0, 1) },
    ],
    nextWeekPlan: '建议保持每日确认工时的习惯，并提前登记跨项目任务。',
  };
  return payload;
}

function dirtyPayload(facts) {
  const entries = Array.isArray(facts.entries) ? facts.entries : [];
  const ids = entries.map((entry) => entry.entryId).filter((id) => typeof id === 'string');
  return {
    summaryPoints: [
      // 编造数字：999 分钟、42 个成果都不在事实白名单里，整句应被丢弃
      { text: '本周共完成 999 分钟，产出 42 个成果。', entryIds: ids.slice(0, 2) },
      // 非法 entryId：引用了本周不存在的条目，整条应被丢弃
      { text: '跨部门协同推进了重点事项。', entryIds: ['00000000-0000-4000-8000-000000000000'] },
      // 合法内容：数字来自事实，entryId 真实，应被保留
      {
        text: `本周共确认 ${facts.confirmedDays} 天，累计 ${facts.totalMinutes} 分钟。`,
        entryIds: ids.slice(0, 2),
      },
    ],
    highlights: [
      { text: '提前交付 7 个里程碑。', entryIds: ['11111111-0000-4000-8000-000000000999'] },
    ],
    // 含编造数字的句子应被丢弃，剩余句子保留
    nextWeekPlan: '下周计划完成 12 个事项。建议提前对齐需求。',
  };
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? '127.0.0.1'}`);
  if (url.pathname === '/__mode') {
    const value = url.searchParams.get('value');
    if (value === 'clean' || value === 'dirty') {
      mode = value;
    }
    response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ mode }));
    return;
  }
  if (url.pathname !== '/chat/completions' || request.method !== 'POST') {
    response.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: 'not found' }));
    return;
  }
  try {
    const body = JSON.parse(await readBody(request));
    const facts = factsFrom(body);
    const payload = mode === 'dirty' ? dirtyPayload(facts) : cleanPayload(facts);
    console.log(
      `[fake-chat] mode=${mode} model=${body.model} confirmedDays=${facts.confirmedDays} `
      + `totalMinutes=${facts.totalMinutes} entries=${facts.entries?.length ?? 0}`,
    );
    response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({
      id: 'chatcmpl-fake-wp04-02',
      object: 'chat.completion',
      model: body.model,
      choices: [{
        index: 0,
        finish_reason: 'stop',
        message: { role: 'assistant', content: JSON.stringify(payload) },
      }],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    }));
  } catch (error) {
    console.error(`[fake-chat] 请求解析失败：${error.message}`);
    response.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: { message: error.message } }));
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[fake-chat] listening on http://127.0.0.1:${PORT} mode=${mode}`);
});
