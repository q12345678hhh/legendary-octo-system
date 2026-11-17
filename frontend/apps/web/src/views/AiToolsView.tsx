import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Rate, Space, Table, Tag, Typography } from 'antd';
import type { AiTool, ApiClient, RegisterAiToolInput } from '@platform/api-client';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError } from '../utils/format';

const { Paragraph, Text } = Typography;

export interface AiToolsViewProps {
  apiClient: ApiClient;
  permissions: Set<string> | null;
}

/** AI 工具目录（插件平台最小版）：登记、启停与评分，不做实际调用。 */
export function AiToolsView({ apiClient, permissions }: AiToolsViewProps) {
  const canRegister = permissions?.has('ai.tool.register') ?? false;
  const canToggle = permissions?.has('ai.tool.enable') ?? false;
  const canRate = permissions?.has('ai.tool.rate') ?? false;
  const [tools, setTools] = useState<AiTool[]>([]);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form] = Form.useForm<RegisterAiToolInput>();
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  function load(): void {
    setPhase('loading');
    setError(null);
    apiClient
      .listAiTools()
      .then((rows) => {
        setTools(rows);
        setPhase(rows.length === 0 ? 'empty' : 'ready');
      })
      .catch((loadError: unknown) => {
        setError(describeError(loadError));
        setPhase('error');
      });
  }

  useEffect(load, [apiClient]);

  async function register(): Promise<void> {
    const values = await form.validateFields();
    setSaving(true);
    setActionError(null);
    try {
      await apiClient.registerAiTool(values);
      setFormOpen(false);
      form.resetFields();
      load();
    } catch (registerError: unknown) {
      setActionError(describeError(registerError));
    } finally {
      setSaving(false);
    }
  }

  async function toggle(tool: AiTool): Promise<void> {
    const target = tool.status === 'enabled' ? 'disabled' : 'enabled';
    setBusyId(tool.id);
    setActionError(null);
    try {
      await apiClient.setAiToolStatus(tool.id, target, tool.version);
      load();
    } catch (toggleError: unknown) {
      setActionError(describeError(toggleError));
    } finally {
      setBusyId(null);
    }
  }

  async function rate(tool: AiTool, rating: number): Promise<void> {
    setBusyId(tool.id);
    setActionError(null);
    try {
      await apiClient.rateAiTool(tool.id, rating, tool.version);
      load();
    } catch (rateError: unknown) {
      setActionError(describeError(rateError));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Card
      title="AI 工具"
      extra={
        canRegister ? (
          <Button type="primary" onClick={() => setFormOpen(true)}>
            登记工具
          </Button>
        ) : null
      }
    >
      <Paragraph type="secondary">
        登记可被 AI 助手调用的工具元数据。首期只管理目录与启停，不执行调用，也不保存密钥。
      </Paragraph>
      {actionError && (
        <Alert type="error" showIcon message="操作失败" description={actionError} style={{ marginBottom: 12 }} />
      )}
      <AsyncState
        phase={phase}
        emptyText="还没有登记任何工具"
        errorTitle="AI 工具加载失败"
        {...(error ? { errorMessage: error } : {})}
      >
        <Table<AiTool>
          size="small"
          rowKey="id"
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={tools}
          columns={[
            {
              title: '工具',
              key: 'tool',
              render: (_, tool) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{tool.name}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {tool.code}
                  </Text>
                </Space>
              ),
            },
            {
              title: '地址',
              dataIndex: 'endpointUrl',
              key: 'endpointUrl',
              ellipsis: true,
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              width: 100,
              render: (status: string) =>
                status === 'enabled' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
            },
            {
              title: '评分',
              key: 'rating',
              width: 150,
              render: (_, tool) =>
                canRate ? (
                  <Rate
                    value={tool.rating ?? 0}
                    count={5}
                    disabled={busyId === tool.id}
                    onChange={(value) => void rate(tool, value)}
                  />
                ) : (
                  <Text type="secondary">{tool.rating ?? '未评分'}</Text>
                ),
            },
            {
              title: '操作',
              key: 'actions',
              width: 110,
              render: (_, tool) =>
                canToggle ? (
                  <Button
                    size="small"
                    danger={tool.status === 'enabled'}
                    loading={busyId === tool.id}
                    disabled={tool.status !== 'enabled' && (tool.rating ?? 0) === 0}
                    title={tool.status !== 'enabled' && (tool.rating ?? 0) === 0
                      ? '启用前需先由他人评分'
                      : undefined}
                    onClick={() => void toggle(tool)}
                  >
                    {tool.status === 'enabled' ? '停用' : '启用'}
                  </Button>
                ) : null,
            },
          ]}
        />
      </AsyncState>

      <Modal
        title="登记 AI 工具"
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void register()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={600}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="code"
            label="工具编码"
            extra="小写字母开头，3-64 位，可含 . _ -；创建后不可改"
            rules={[
              { required: true, message: '请填写工具编码' },
              { pattern: /^[a-z][a-z0-9._-]{2,63}$/, message: '格式不合法' },
            ]}
          >
            <Input placeholder="doc.search" />
          </Form.Item>
          <Form.Item
            name="name"
            label="工具名称"
            rules={[{ required: true, message: '请填写工具名称' }]}
          >
            <Input placeholder="文档检索" maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} placeholder="这个工具能做什么" maxLength={500} />
          </Form.Item>
          <Form.Item
            name="endpointUrl"
            label="调用地址"
            rules={[
              { required: true, message: '请填写调用地址' },
              { pattern: /^https?:\/\//, message: '需以 http:// 或 https:// 开头' },
            ]}
          >
            <Input placeholder="https://example.com/api/doc-search" />
          </Form.Item>
          <Form.Item
            name="inputSchema"
            label="入参结构（JSON，可选）"
            extra="留空等价于 {}"
          >
            <Input.TextArea rows={4} placeholder={'{\n  "type": "object"\n}'} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
