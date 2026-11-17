import type { ModuleRoute } from '@platform/api-client';
import { Card, Space, Tag, Typography } from 'antd';

export interface ModuleLandingViewProps {
  module: ModuleRoute;
}

/** 动态注册模块的通用落地页：无内置页面时仍给用户一个明确入口。 */
export function ModuleLandingView({ module }: ModuleLandingViewProps) {
  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center" size="middle">
          <Typography.Title level={4} style={{ margin: 0 }}>
            {module.name}
          </Typography.Title>
          <Tag color="blue">已启用</Tag>
        </Space>
        <Typography.Text type="secondary">模块编码：{module.code}</Typography.Text>
        <Typography.Text type="secondary">菜单路由：{module.routePath}</Typography.Text>
        <Typography.Paragraph style={{ marginBottom: 0 }}>
          该应用由模块中心登记并动态注册。具体业务页面由模块提供方接入后在此呈现。
        </Typography.Paragraph>
      </Space>
    </Card>
  );
}
