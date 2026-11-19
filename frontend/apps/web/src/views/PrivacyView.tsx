import type {
  ApiClient,
  PrivacyDataClassInfo,
  PrivacyExpenseData,
  PrivacyMyData,
  PrivacySettings,
  PrivacySettingsInput,
  PrivacyVoiceData,
} from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  InputNumber,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

const DATA_CLASS_LABEL: Record<string, string> = {
  file: '文件与附件',
  worklog_entry: '工时记录',
  worklog_weekly_report: '周报',
  worklog_voice_capture: '语音转写',
  expense_document: '单据字段',
  expense_extraction: '单据 OCR',
  audit: '审计记录',
};

const VOICE_STATUS_LABEL: Record<string, string> = {
  uploaded: '待转写',
  transcribed: '已转写',
  confirmed: '已生成工时',
  discarded: '已放弃',
  failed: '转写失败',
};

const EXPENSE_STATUS_LABEL: Record<string, string> = {
  manual_review: '待人工复核',
  approved: '已通过待入账',
  posted: '已入账',
  rejected: '已驳回',
  voided: '已撤销',
};

export interface PrivacyViewProps {
  apiClient: ApiClient;
  permissions?: Set<string> | null;
}

function allowed(permissions: Set<string> | null | undefined, code: string): boolean {
  return permissions == null || permissions.has(code);
}

export function PrivacyView({ apiClient, permissions = null }: PrivacyViewProps) {
  const canAdmin = allowed(permissions, 'tenant.admin');

  const [myData, setMyData] = useState<PrivacyMyData | null>(null);
  const [myDataError, setMyDataError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedVoice, setSelectedVoice] = useState<string[]>([]);
  const [selectedExpense, setSelectedExpense] = useState<string[]>([]);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);

  const [settings, setSettings] = useState<PrivacySettings | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [dataClasses, setDataClasses] = useState<PrivacyDataClassInfo[] | null>(null);
  const [draft, setDraft] = useState<PrivacySettingsInput | null>(null);
  const [saving, setSaving] = useState(false);

  const loadMyData = useCallback(() => {
    setMyDataError(null);
    return apiClient
      .getMyPrivacyData()
      .then((data) => {
        setMyData(data);
        setSelectedVoice([]);
        setSelectedExpense([]);
      })
      .catch((error: unknown) => setMyDataError(describeError(error)));
  }, [apiClient]);

  const loadSettings = useCallback(() => {
    if (!canAdmin) {
      return Promise.resolve();
    }
    setSettingsError(null);
    return Promise.all([apiClient.getPrivacySettings(), apiClient.listPrivacyDataClasses()])
      .then(([loaded, classes]) => {
        setSettings(loaded);
        setDraft({
          dataResidency: loaded.dataResidency,
          trainingOptOut: loaded.trainingOptOut,
          voiceTranscriptRetentionDays: loaded.voiceTranscriptRetentionDays,
          expenseOcrRetentionDays: loaded.expenseOcrRetentionDays,
        });
        setDataClasses(classes);
      })
      .catch((error: unknown) => setSettingsError(describeError(error)));
  }, [apiClient, canAdmin]);

  useEffect(() => {
    void loadMyData();
  }, [loadMyData]);

  useEffect(() => {
    void loadSettings();
  }, [loadSettings]);

  const handleExport = async (): Promise<void> => {
    setExporting(true);
    setNotice(null);
    try {
      const exported = await apiClient.exportMyPrivacyData();
      if (typeof URL.createObjectURL === 'function') {
        const blob = new Blob([JSON.stringify(exported, null, 2)], {
          type: 'application/json',
        });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `my-data-${new Date().toISOString().slice(0, 10)}.json`;
        anchor.click();
        URL.revokeObjectURL(url);
      }
      setNotice('已导出我的数据（不含文件字节）');
    } catch (error) {
      setMyDataError(describeError(error));
    } finally {
      setExporting(false);
    }
  };

  const handleDelete = async (): Promise<void> => {
    if (selectedVoice.length === 0 && selectedExpense.length === 0) {
      setNotice('请先勾选要删除的语音或单据');
      return;
    }
    setDeleting(true);
    setNotice(null);
    try {
      const result = await apiClient.deleteMyPrivacyData({
        voiceCaptureIds: selectedVoice,
        expenseDocumentIds: selectedExpense,
      });
      const anonymized = result.actions.filter((action) => action.action === 'anonymized').length;
      message.success(anonymized > 0
        ? `已处理 ${result.actions.length} 条，其中 ${anonymized} 条已入账单据只匿名化 OCR`
        : `已移入回收站 ${result.actions.length} 条`);
      await loadMyData();
    } catch (error) {
      setMyDataError(describeError(error));
    } finally {
      setDeleting(false);
    }
  };

  const handleSave = async (): Promise<void> => {
    if (!draft) {
      return;
    }
    setSaving(true);
    setSettingsError(null);
    try {
      const saved = await apiClient.updatePrivacySettings(draft);
      setSettings(saved);
      message.success('隐私设置已保存');
    } catch (error) {
      setSettingsError(describeError(error));
    } finally {
      setSaving(false);
    }
  };

  const voiceColumns = [
    {
      title: '录音',
      dataIndex: 'id',
      render: (id: string) => <Text code>{shortId(id)}</Text>,
    },
    {
      title: '工作日期',
      dataIndex: 'workDate',
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (status: string) => (
        <Tag>{VOICE_STATUS_LABEL[status] ?? status}</Tag>
      ),
    },
    {
      title: '转写',
      dataIndex: 'transcribed',
      render: (transcribed: boolean) => (transcribed ? '有转写文本' : '无'),
    },
    {
      title: '音频删除时间',
      dataIndex: 'audioDeletedAt',
      render: (value: string | null | undefined) => (value ? formatDateTime(value) : '—'),
    },
  ];

  const expenseColumns = [
    {
      title: '单据',
      dataIndex: 'id',
      render: (id: string) => <Text code>{shortId(id)}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'docType',
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (status: string) => <Tag>{EXPENSE_STATUS_LABEL[status] ?? status}</Tag>,
    },
    {
      title: '金额',
      dataIndex: 'amount',
      render: (amount: number | null | undefined, row: PrivacyExpenseData) =>
        (amount == null ? '—' : `${row.currency ?? 'CNY'} ${amount.toFixed(2)}`),
    },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      render: (value: string | null | undefined) => (value ? formatDateTime(value) : '—'),
    },
    {
      title: '删除方式',
      dataIndex: 'status',
      render: (status: string) =>
        (status === 'posted' ? '只匿名化 OCR，不可删除' : '可移入回收站'),
    },
  ];

  const voiceData: PrivacyVoiceData[] = myData?.voiceCaptures ?? [];
  const expenseData: PrivacyExpenseData[] = myData?.expenseDocuments ?? [];

  const myDataPanel = (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <Button onClick={() => void loadMyData()}>刷新</Button>
        <Button loading={exporting} onClick={() => void handleExport()}>
          导出我的数据
        </Button>
        <Button
          danger
          loading={deleting}
          onClick={() => void handleDelete()}
          disabled={selectedVoice.length === 0 && selectedExpense.length === 0}
        >
          删除选中
        </Button>
        <Text type="secondary">
          已入账单据属于会计档案，只能匿名化 OCR 文本，金额与状态保持不变
        </Text>
      </Space>

      {notice && <Alert type="info" showIcon message={notice} />}
      {myDataError && <Alert type="error" showIcon message={myDataError} />}

      <Card title="我的语音转写" size="small">
        <Table
          size="small"
          rowKey="id"
          dataSource={voiceData}
          columns={voiceColumns}
          pagination={false}
          locale={{ emptyText: '暂无语音记录' }}
          rowSelection={{
            selectedRowKeys: selectedVoice,
            onChange: (keys) => setSelectedVoice(keys as string[]),
          }}
        />
      </Card>

      <Card title="我的单据" size="small">
        <Table
          size="small"
          rowKey="id"
          dataSource={expenseData}
          columns={expenseColumns}
          pagination={false}
          locale={{ emptyText: '暂无单据' }}
          rowSelection={{
            selectedRowKeys: selectedExpense,
            onChange: (keys) => setSelectedExpense(keys as string[]),
          }}
        />
      </Card>
    </Space>
  );

  const settingsPanel = canAdmin ? (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {settingsError && <Alert type="error" showIcon message={settingsError} />}
      {draft && (
        <Card title="驻留与保留" size="small">
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Space>
              <Text>数据驻留地</Text>
              <Select
                style={{ width: 160 }}
                value={draft.dataResidency}
                onChange={(value) => setDraft({ ...draft, dataResidency: value })}
                options={[
                  { value: 'cn', label: '中国大陆' },
                  { value: 'eu', label: '欧盟' },
                  { value: 'us', label: '美国' },
                  { value: 'sg', label: '新加坡' },
                ]}
              />
            </Space>
            <Space>
              <Text>要求供应商不将数据用于模型训练</Text>
              <Switch
                checked={draft.trainingOptOut}
                onChange={(checked) => setDraft({ ...draft, trainingOptOut: checked })}
              />
            </Space>
            <Space>
              <Text>语音转写保留天数</Text>
              <InputNumber
                min={1}
                max={3650}
                value={draft.voiceTranscriptRetentionDays}
                onChange={(value) => setDraft({
                  ...draft,
                  voiceTranscriptRetentionDays: Number(value ?? 1),
                })}
              />
            </Space>
            <Space>
              <Text>单据 OCR 保留天数</Text>
              <InputNumber
                min={1}
                max={3650}
                value={draft.expenseOcrRetentionDays}
                onChange={(value) => setDraft({
                  ...draft,
                  expenseOcrRetentionDays: Number(value ?? 1),
                })}
              />
            </Space>
            <Button type="primary" loading={saving} onClick={() => void handleSave()}>
              保存设置
            </Button>
            {settings?.updatedAt && (
              <Text type="secondary">
                最近更新：{formatDateTime(settings.updatedAt)}
                {settings.updatedBy ? ` · ${settings.updatedBy}` : ''}
              </Text>
            )}
          </Space>
        </Card>
      )}

      {settings && (
        <Card title="供应商与模型" size="small">
          <Table
            size="small"
            rowKey="surface"
            pagination={false}
            dataSource={settings.suppliers}
            columns={[
              { title: '用途', dataIndex: 'surface' },
              { title: '供应商', dataIndex: 'provider' },
              { title: '模型', dataIndex: 'model' },
              {
                title: '状态',
                dataIndex: 'configured',
                render: (configured: boolean) => (
                  <Tag color={configured ? 'green' : 'default'}>
                    {configured ? '已配置' : '未配置'}
                  </Tag>
                ),
              },
            ]}
          />
          <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            驻留地 {settings.dataResidency}；训练开关
            {settings.trainingOptOut ? '已开启（不用于训练）' : '未开启'}。
          </Paragraph>
        </Card>
      )}

      <Card title="数据分类与访问角色" size="small">
        <Table
          size="small"
          rowKey="dataClass"
          pagination={false}
          dataSource={dataClasses ?? []}
          columns={[
            {
              title: '数据类',
              dataIndex: 'dataClass',
              render: (value: string) => DATA_CLASS_LABEL[value] ?? value,
            },
            { title: '用途', dataIndex: 'purpose' },
            { title: '保留天数', dataIndex: 'retentionDays' },
            { title: '说明', dataIndex: 'retentionNote' },
            {
              title: '访问角色',
              dataIndex: 'accessRoles',
              render: (roles: string[]) => roles.map((role) => <Tag key={role}>{role}</Tag>),
            },
          ]}
        />
      </Card>
    </Space>
  ) : (
    <Alert type="info" showIcon message="数据分类与供应商信息仅租户管理员可见" />
  );

  const items = [
    { key: 'my-data', label: '我的语音与单据', children: myDataPanel },
    ...(canAdmin ? [{ key: 'settings', label: '数据分类与供应商', children: settingsPanel }] : []),
  ];

  return <Tabs items={items} />;
}
