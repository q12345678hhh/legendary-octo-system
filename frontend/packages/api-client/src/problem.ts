/**
 * RFC 9457 Problem Details。平台所有错误响应统一使用该结构（见契约治理规范）。
 */
export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  correlationId?: string;
  errors?: ReadonlyArray<{ field: string; message: string }>;
}

export function isProblemDetails(value: unknown): value is ProblemDetails {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Record<string, unknown>;
  return typeof candidate.type === 'string'
    && typeof candidate.title === 'string'
    && typeof candidate.status === 'number';
}
