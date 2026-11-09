import { describe, expect, it } from 'vitest';
import { toCssVariables } from './css-variables';
import { tokens } from './tokens';

describe('toCssVariables', () => {
  it('uses the platform prefix and kebab-case names', () => {
    const variables = toCssVariables();

    expect(variables['--platform-color-brand']).toBe(tokens.color.brand);
    expect(variables['--platform-font-size-body']).toBe('14');
    expect(variables['--platform-spacing-md']).toBe('16');
  });

  it('covers every token leaf', () => {
    const variables = toCssVariables();
    const leafCount = Object.values(tokens).reduce(
      (total, group) => total + Object.keys(group).length,
      0,
    );

    expect(Object.keys(variables)).toHaveLength(leafCount);
  });
});
