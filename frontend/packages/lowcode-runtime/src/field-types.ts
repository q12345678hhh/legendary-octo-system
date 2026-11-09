/**
 * 低代码字段类型的服务端同构校验规则。
 * 前端只做即时提示，服务端仍以同一份规则做最终校验（见 PLAN 低代码章节）。
 */
export type FieldType = 'text' | 'long-text' | 'number' | 'date' | 'boolean' | 'enum';

export interface FieldOption {
  label: string;
  value: string;
}

export interface FieldDefinition {
  key: string;
  label: string;
  type: FieldType;
  required?: boolean;
  options?: ReadonlyArray<FieldOption>;
}

export type FieldValue = string | number | boolean | null | undefined;

export type RecordValues = Readonly<Record<string, FieldValue>>;

export interface ValidationIssue {
  field: string;
  code: 'required' | 'type' | 'option' | 'unknown-field';
  message: string;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function validateRecord(
  fields: ReadonlyArray<FieldDefinition>,
  values: RecordValues,
): ReadonlyArray<ValidationIssue> {
  const issues: ValidationIssue[] = [];
  const knownKeys = new Set(fields.map((field) => field.key));

  for (const key of Object.keys(values)) {
    if (!knownKeys.has(key)) {
      issues.push({ field: key, code: 'unknown-field', message: `未声明的字段: ${key}` });
    }
  }

  for (const field of fields) {
    const value = values[field.key];
    const empty = value === undefined || value === null || value === '';

    if (empty) {
      if (field.required === true) {
        issues.push({ field: field.key, code: 'required', message: `${field.label}为必填` });
      }
      continue;
    }

    if (field.type === 'number' && typeof value !== 'number') {
      issues.push({ field: field.key, code: 'type', message: `${field.label}必须是数字` });
    }
    if (field.type === 'boolean' && typeof value !== 'boolean') {
      issues.push({ field: field.key, code: 'type', message: `${field.label}必须是布尔值` });
    }
    if (field.type === 'date' && (typeof value !== 'string' || !ISO_DATE.test(value))) {
      issues.push({ field: field.key, code: 'type', message: `${field.label}必须是 yyyy-MM-dd` });
    }
    if (field.type === 'text' && typeof value !== 'string') {
      issues.push({ field: field.key, code: 'type', message: `${field.label}必须是文本` });
    }
    if (field.type === 'enum') {
      const allowed = (field.options ?? []).map((option) => option.value);
      if (typeof value !== 'string' || !allowed.includes(value)) {
        issues.push({ field: field.key, code: 'option', message: `${field.label}不在可选范围内` });
      }
    }
  }

  return issues;
}
