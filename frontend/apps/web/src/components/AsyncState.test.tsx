import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AsyncState } from './AsyncState';

afterEach(cleanup);

describe('AsyncState', () => {
  it('announces loading state politely', () => {
    render(<AsyncState phase="loading" loadingText="正在读取作业…" />);

    const status = screen.getByRole('status');
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(screen.getByText('正在读取作业…')).toBeTruthy();
  });

  it('shows an empty state', () => {
    render(<AsyncState phase="empty" emptyText="还没有作业" />);

    expect(screen.getByRole('status')).toBeTruthy();
    expect(screen.getByText('还没有作业')).toBeTruthy();
  });

  it('exposes errors as alerts and retries on demand', () => {
    const onRetry = vi.fn();
    render(
      <AsyncState
        phase="error"
        errorTitle="无法连接后端服务"
        errorMessage="Failed to fetch"
        onRetry={onRetry}
      />,
    );

    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText('无法连接后端服务')).toBeTruthy();
    expect(screen.getByText('Failed to fetch')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps partial data visible with a warning', () => {
    render(
      <AsyncState phase="partial" partialText="部分数据加载失败">
        <p>已有内容</p>
      </AsyncState>,
    );

    expect(screen.getByText('部分数据加载失败')).toBeTruthy();
    expect(screen.getByText('已有内容')).toBeTruthy();
  });

  it('renders children when ready', () => {
    render(
      <AsyncState phase="ready">
        <p>就绪内容</p>
      </AsyncState>,
    );

    expect(screen.getByText('就绪内容')).toBeTruthy();
  });
});
