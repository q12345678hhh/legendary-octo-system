import { describe, expect, it } from 'vitest';
import { toCssVariables } from './css-variables';
import { tokens } from './tokens';

function countLeaves(value: unknown): number {
  if (value === null || typeof value !== 'object') {
    return 1;
  }
  return Object.values(value).reduce<number>((total, nested) => total + countLeaves(nested), 0);
}

describe('toCssVariables', () => {
  it('uses the platform prefix and kebab-case names', () => {
    const variables = toCssVariables();

    expect(variables['--platform-color-brand']).toBe(tokens.color.brand);
    expect(variables['--platform-font-size-body']).toBe('14');
    expect(variables['--platform-spacing-md']).toBe('16');
  });

  it('exposes focus ring and control heights for accessible shells', () => {
    const variables = toCssVariables();

    expect(variables['--platform-focus-ring']).toBe(tokens.focusRing);
    expect(variables['--platform-control-height']).toBe('40');
    expect(variables['--platform-control-height-sm']).toBe('32');
  });

  it('covers every token leaf', () => {
    const variables = toCssVariables();

    expect(Object.keys(variables)).toHaveLength(countLeaves(tokens));
  });
});
