import { ApiProblemError } from '@platform/api-client';

/** 把未知异常转成可展示文案，优先使用后端 Problem Details 的 detail/code/追踪号。 */
export function describeError(error: unknown, fallback = '请求失败，请稍后重试。'): string {
  if (error instanceof ApiProblemError) {
    const problem = error.problem;
    if (problem) {
      const code = problem.code ? `（${problem.code}）` : '';
      const detail = problem.detail ?? problem.title;
      const correlation = problem.correlationId ? ` 追踪号 ${problem.correlationId}` : '';
      return `${detail}${code}${correlation}`;
    }
    return `HTTP ${error.status}`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return fallback;
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function shortId(value: string, length = 8): string {
  return value.length <= length ? value : value.slice(0, length);
}
