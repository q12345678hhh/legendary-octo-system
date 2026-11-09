/**
 * 提交信息规范：Conventional Commits 子集 + 项目自定义约束。
 *
 * 规则全部内联，不 extends 外部预设，避免 CI 需要额外解析依赖。
 * 允许类型与 PLAN.md 的工作包语义对齐：feat/fix/docs/refactor/test/perf/build/ci/chore/revert。
 */
export default {
  rules: {
    'type-enum': [
      2,
      'always',
      ['feat', 'fix', 'docs', 'refactor', 'test', 'perf', 'build', 'ci', 'chore', 'revert'],
    ],
    'type-case': [2, 'always', 'lower-case'],
    'type-empty': [2, 'never'],
    'scope-empty': [1, 'never'],
    'subject-empty': [2, 'never'],
    'subject-full-stop': [2, 'never', '.'],
    'header-max-length': [2, 'always', 100],
    'body-max-line-length': [1, 'always', 120],
  },
};
