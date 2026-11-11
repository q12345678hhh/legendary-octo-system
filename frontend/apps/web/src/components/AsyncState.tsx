import { Alert, Button, Empty, Space, Spin, Typography } from 'antd';
import type { ReactNode } from 'react';

export type AsyncPhase = 'loading' | 'ready' | 'empty' | 'error' | 'partial';

export interface AsyncStateProps {
  phase: AsyncPhase;
  children?: ReactNode;
  loadingText?: string;
  emptyText?: string;
  errorTitle?: string;
  errorMessage?: string;
  partialText?: string;
  onRetry?: () => void;
}

/**
 * 统一加载/空/错/部分可用四态，供所有页面复用。
 * - 加载与空态使用 aria-live 播报；
 * - 错误使用 role="alert" 并给出重试按钮；
 * - partial 表示拿到了部分数据但仍需提示。
 */
export function AsyncState({
  phase,
  children,
  loadingText = '正在加载…',
  emptyText = '暂无数据',
  errorTitle = '加载失败',
  errorMessage = '请稍后重试，或检查后端服务是否可用。',
  partialText = '部分数据加载失败，以下内容可能不完整。',
  onRetry,
}: AsyncStateProps) {
  if (phase === 'loading') {
    return (
      <div role="status" aria-live="polite">
        <Space>
          <Spin size="small" />
          <Typography.Text type="secondary">{loadingText}</Typography.Text>
        </Space>
      </div>
    );
  }

  if (phase === 'empty') {
    return (
      <div role="status" aria-live="polite">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />
      </div>
    );
  }

  if (phase === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message={errorTitle}
        description={errorMessage}
        action={
          onRetry ? (
            <Button size="small" onClick={onRetry}>
              重试
            </Button>
          ) : null
        }
      />
    );
  }

  if (phase === 'partial') {
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          type="warning"
          showIcon
          message={partialText}
          action={
            onRetry ? (
              <Button size="small" onClick={onRetry}>
                重试
              </Button>
            ) : null
          }
        />
        {children}
      </Space>
    );
  }

  return <>{children}</>;
}
