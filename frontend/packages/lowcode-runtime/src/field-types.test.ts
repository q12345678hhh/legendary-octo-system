import { describe, expect, it } from 'vitest';
import { validateRecord, type FieldDefinition } from './field-types';

const fields: ReadonlyArray<FieldDefinition> = [
  { key: 'title', label: '标题', type: 'text', required: true },
  { key: 'amount', label: '金额', type: 'number' },
  { key: 'dueOn', label: '截止日', type: 'date' },
  { key: 'priority', label: '优先级', type: 'enum', options: [{ label: '高', value: 'high' }, { label: '低', value: 'low' }] },
];

describe('validateRecord', () => {
  it('accepts a well-formed record', () => {
    expect(validateRecord(fields, { title: '季度复盘', amount: 120, dueOn: '2026-11-30', priority: 'high' })).toEqual([]);
  });

  it('reports missing required fields, wrong types and unknown keys', () => {
    const issues = validateRecord(fields, { amount: '120', dueOn: '2026/11/30', priority: 'urgent', extra: 1 });
    const codes = issues.map((issue) => `${issue.field}:${issue.code}`);

    expect(codes).toContain('title:required');
    expect(codes).toContain('amount:type');
    expect(codes).toContain('dueOn:type');
    expect(codes).toContain('priority:option');
    expect(codes).toContain('extra:unknown-field');
  });

  it('treats empty optional values as absent', () => {
    expect(validateRecord(fields, { title: '周报', amount: null })).toEqual([]);
  });
});
