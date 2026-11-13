import type {
  ApiClient,
  AppModule,
  AppModuleHealth,
  ModuleSuggestion,
  ModuleSuggestionDetail,
  ModuleSuggestionStatus,
} from '@platform/api-client';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Segmented,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

export interface ModulesViewProps {
  apiClient: ApiClient;
  /** 能力清单；null 表示尚未拿到，按「全部可见」渲染，后端仍逐请求鉴权。 */
  permissions: Set<string> | null;
}

const STATUS_LABEL: Record<ModuleSuggestionStatus, string> = {
  draft: '草稿',
  communicating: '沟通中',
  submitted: '待受理',
  configuring: '配置中',
  testing: '测试中',
  rectifying: '整改',
  test_failed: '测试失败',
  pending_final_approval: '待最终批准',
  rejected: '驳回',
  withdrawn: '撤回',
  enabled: '启用',
  special_pending_review: '特殊启用待复核',
  paused: '暂停',
  closed: '关闭',
  deprecated: '弃用',
};

const STATUS_COLOR: Record<ModuleSuggestionStatus, string> = {
  draft: 'default',
  communicating: 'blue',
  submitted: 'gold',
  configuring: 'cyan',
  testing: 'geekblue',
  rectifying: 'orange',
  test_failed: 'red',
  pending_final_approval: 'purple',
  rejected: 'red',
  withdrawn: 'default',
  enabled: 'green',
  special_pending_review: 'magenta',
  paused: 'orange',
  closed: 'default',
  deprecated: 'default',
};

interface ActionDef {
  label: string;
  review: boolean;
  needsComment?: boolean;
  needsOwner?: boolean;
}

const ACTIONS: Record<string, ActionDef> = {
  create: { label: '发起建议', review: false },
  'config-owner-assigned': { label: '指定配置负责人', review: true },
  submit: { label: '提交沟通', review: false },
  'submit-review': { label: '提交受理', review: false },
  withdraw: { label: '撤回', review: false },
  accept: { label: '受理并指定配置负责人', review: true, needsOwner: true },
  reject: { label: '驳回', review: true, needsComment: true },
  'request-more': { label: '要求补充', review: true, needsComment: true },
  comment: { label: '记录沟通意见', review: true, needsComment: true },
  'start-test': { label: '开始测试', review: true },
  'test-pass': { label: '测试通过', review: true },
  'test-fail': { label: '测试不通过', review: true, needsComment: true },
  rectify: { label: '进入整改', review: true },
  approve: { label: '最终批准启用', review: true },
  'special-review': { label: '转特殊启用复核', review: true },
  'review-pass': { label: '复核通过', review: true },
  pause: { label: '暂停', review: true },
  resume: { label: '恢复', review: true },
  deprecate: { label: '弃用', review: true },
  close: { label: '关闭', review: true },
};

const ACTIONS_BY_STATUS: Record<ModuleSuggestionStatus, string[]> = {
  draft: ['submit', 'withdraw'],
  communicating: ['submit-review', 'comment', 'withdraw'],
  submitted: ['accept', 'reject', 'request-more', 'comment', 'withdraw'],
  configuring: ['start-test'],
  testing: ['test-pass', 'test-fail'],
  rectifying: ['start-test'],
  test_failed: ['rectify'],
  pending_final_approval: ['approve'],
  rejected: ['close'],
  withdrawn: ['close'],
  enabled: ['special-review', 'pause', 'deprecate', 'close'],
  special_pending_review: ['review-pass', 'close'],
  paused: ['resume', 'deprecate', 'close'],
  closed: [],
  deprecated: [],
};

const HEALTH_LABEL: Record<AppModuleHealth, string> = {
  healthy: '健康',
  degraded: '降级',
  offline: '离线',
  unknown: '未知',
};

const HEALTH_COLOR: Record<AppModuleHealth, string> = {
  healthy: 'green',
  degraded: 'orange',
  offline: 'red',
  unknown: 'default',
};

const INSTALL_LABEL: Record<string, string> = {
  not_installed: '未安装',
  installed: '已安装',
  disabled: '已停用',
};

const SCOPE_LABEL: Record<string, string> = {
  tenant: '全租户',
  department: '部门',
  project: '项目',
  person: '个人',
};

type TabKey = 'catalog' | 'suggestions';

/** 模块中心：目录检索 + 模块建议与状态流转。 */
export function ModulesView({ apiClient, permissions }: ModulesViewProps) {
  const canReadCatalog = permissions === null || permissions.has('appcatalog.module.read');
  const canManage = permissions === null || permissions.has('appcatalog.module.manage');
  const canReview = permissions?.has('appcatalog.suggestion.review') ?? false;
  const canCreate = permissions === null || permissions.has('appcatalog.suggestion.create');

  const [tab, setTab] = useState<TabKey>(canReadCatalog ? 'catalog' : 'suggestions');

  useEffect(() => {
    if (permissions && !permissions.has('appcatalog.module.read') && tab === 'catalog') {
      setTab('suggestions');
    }
  }, [permissions, tab]);

  // ------------------------------------------------------------------ 目录
  const [modules, setModules] = useState<AppModule[]>([]);
  const [catalogPhase, setCatalogPhase] = useState<AsyncPhase>('loading');
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState<string | undefined>(undefined);
  const [health, setHealth] = useState<string | undefined>(undefined);
  const [moduleOpen, setModuleOpen] = useState(false);
  const [savingModule, setSavingModule] = useState(false);
  const [moduleForm] = Form.useForm();

  const loadCatalog = useCallback(() => {
    if (!canReadCatalog) {
      setModules([]);
      setCatalogPhase('empty');
      return;
    }
    setCatalogPhase('loading');
    setCatalogError(null);
    apiClient
      .listAppModules({ q: keyword, category, health })
      .then((list) => {
        setModules(list);
        setCatalogPhase(list.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        setCatalogError(describeError(cause));
        setCatalogPhase('error');
      });
  }, [apiClient, canReadCatalog, keyword, category, health]);

  useEffect(() => {
    if (tab === 'catalog') {
      loadCatalog();
    }
  }, [tab, loadCatalog]);

  // ------------------------------------------------------------------ 建议
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const [suggestions, setSuggestions] = useState<ModuleSuggestion[]>([]);
  const [suggestionPhase, setSuggestionPhase] = useState<AsyncPhase>('loading');
  const [suggestionError, setSuggestionError] = useState<string | null>(null);
  const [suggestionOpen, setSuggestionOpen] = useState(false);
  const [savingSuggestion, setSavingSuggestion] = useState(false);
  const [suggestionForm] = Form.useForm();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<ModuleSuggestionDetail | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [actionForm] = Form.useForm();
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!canReview && scope === 'all') {
      setScope('mine');
    }
  }, [canReview, scope]);

  const loadSuggestions = useCallback(() => {
    setSuggestionPhase('loading');
    setSuggestionError(null);
    apiClient
      .listModuleSuggestions(scope)
      .then((list) => {
        setSuggestions(list);
        setSuggestionPhase(list.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        setSuggestionError(describeError(cause));
        setSuggestionPhase('error');
      });
  }, [apiClient, scope]);

  useEffect(() => {
    if (tab === 'suggestions') {
      loadSuggestions();
    }
  }, [tab, loadSuggestions]);

  function openDetail(suggestionId: string): void {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetail(null);
    setActionError(null);
    apiClient
      .getModuleSuggestion(suggestionId)
      .then(setDetail)
      .catch((cause: unknown) => setActionError(describeError(cause)))
      .finally(() => setDetailLoading(false));
  }

  function submitModule(values: Record<string, string>): void {
    setSavingModule(true);
    apiClient
      .createAppModule({
        code: values.code ?? '',
        name: values.name ?? '',
        category: values.category,
        description: values.description,
        version: values.version,
        dependencies: values.dependencies,
        applicableScope: (values.applicableScope as AppModule['applicableScope']) ?? 'tenant',
        healthStatus: (values.healthStatus as AppModuleHealth) ?? 'unknown',
        installStatus: 'not_installed',
      })
      .then(() => {
        setModuleOpen(false);
        moduleForm.resetFields();
        loadCatalog();
      })
      .catch((cause: unknown) => setCatalogError(describeError(cause)))
      .finally(() => setSavingModule(false));
  }

  function submitSuggestion(values: Record<string, string>): void {
    setSavingSuggestion(true);
    apiClient
      .createModuleSuggestion({
        title: values.title ?? '',
        businessProblem: values.businessProblem ?? '',
        goal: values.goal,
        scope: values.scope,
        dataScope: values.dataScope,
        permissionsNeeded: values.permissionsNeeded,
        costEstimate: values.costEstimate,
        benefitEstimate: values.benefitEstimate,
        suggestedOwnerPersonId: values.suggestedOwnerPersonId || null,
      })
      .then(() => {
        setSuggestionOpen(false);
        suggestionForm.resetFields();
        if (tab !== 'suggestions') {
          setTab('suggestions');
        } else {
          loadSuggestions();
        }
      })
      .catch((cause: unknown) => setSuggestionError(describeError(cause)))
      .finally(() => setSavingSuggestion(false));
  }

  function runAction(action: string, comment = '', configOwnerPersonId?: string): void {
    if (!detail) {
      return;
    }
    setActing(true);
    setActionError(null);
    apiClient
      .actOnModuleSuggestion(detail.suggestion.id, {
        action,
        comment,
        configOwnerPersonId: configOwnerPersonId || null,
      })
      .then((next) => {
        setDetail(next);
        setPendingAction(null);
        actionForm.resetFields();
        loadSuggestions();
      })
      .catch((cause: unknown) => setActionError(describeError(cause)))
      .finally(() => setActing(false));
  }

  const availableActions = detail
    ? (ACTIONS_BY_STATUS[detail.suggestion.status] ?? []).filter((action) => {
        const definition = ACTIONS[action];
        if (!definition) {
          return false;
        }
        return definition.review ? canReview : canCreate;
      })
    : [];

  const pendingDefinition = pendingAction ? ACTIONS[pendingAction] : undefined;

  return (
    <>
      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as TabKey)}
        items={[
        {
          key: 'catalog',
          label: '模块目录',
          children: (
            <Card
              title="模块目录"
              extra={
                <Space wrap>
                  <Input.Search
                    allowClear
                    placeholder="搜索编码 / 名称 / 分类"
                    style={{ width: 240 }}
                    onSearch={(value) => setKeyword(value)}
                  />
                  <Select
                    allowClear
                    placeholder="分类"
                    style={{ width: 130 }}
                    value={category}
                    onChange={setCategory}
                    options={Array.from(new Set(modules.map((item) => item.category))).map(
                      (value) => ({ value, label: value }),
                    )}
                  />
                  <Select
                    allowClear
                    placeholder="健康状态"
                    style={{ width: 130 }}
                    value={health}
                    onChange={setHealth}
                    options={Object.entries(HEALTH_LABEL).map(([value, label]) => ({
                      value,
                      label,
                    }))}
                  />
                  {canManage && (
                    <Button type="primary" onClick={() => setModuleOpen(true)}>
                      新增模块
                    </Button>
                  )}
                </Space>
              }
            >
              <Paragraph type="secondary">
                目录只登记模块的负责人、版本、依赖、适用范围与健康状态；安装动作在本阶段仅保留占位。
              </Paragraph>
              {canReadCatalog ? (
                <AsyncState
                  phase={catalogPhase}
                  emptyText="目录还没有模块，先登记一个吧。"
                  errorTitle="模块目录加载失败"
                  {...(catalogError ? { errorMessage: catalogError } : {})}
                  onRetry={loadCatalog}
                >
                  <Table<AppModule>
                    size="small"
                    rowKey="id"
                    pagination={false}
                    dataSource={modules}
                    columns={[
                      {
                        title: '模块',
                        dataIndex: 'name',
                        render: (_value, record) => (
                          <Space direction="vertical" size={0}>
                            <Text strong>{record.name}</Text>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {record.code} · {record.description || '暂无说明'}
                            </Text>
                          </Space>
                        ),
                      },
                      { title: '分类', dataIndex: 'category', width: 110 },
                      {
                        title: '负责人',
                        dataIndex: 'ownerPersonId',
                        width: 120,
                        render: (value: string | null) =>
                          value ? shortId(value) : <Text type="secondary">未指定</Text>,
                      },
                      { title: '版本', dataIndex: 'version', width: 90 },
                      {
                        title: '适用范围',
                        dataIndex: 'applicableScope',
                        width: 110,
                        render: (value: string) => SCOPE_LABEL[value] ?? value,
                      },
                      {
                        title: '健康',
                        dataIndex: 'healthStatus',
                        width: 90,
                        render: (value: AppModuleHealth) => (
                          <Tag color={HEALTH_COLOR[value] ?? 'default'}>
                            {HEALTH_LABEL[value] ?? value}
                          </Tag>
                        ),
                      },
                      {
                        title: '安装',
                        dataIndex: 'installStatus',
                        width: 90,
                        render: (value: string) => INSTALL_LABEL[value] ?? value,
                      },
                    ]}
                  />
                </AsyncState>
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message="当前角色没有模块目录查看权限"
                  description="你仍可在「模块建议」中发起建议并跟踪处理进度。"
                />
              )}
            </Card>
          ),
        },
        {
          key: 'suggestions',
          label: '模块建议',
          children: (
            <Card
              title="模块建议"
              extra={
                <Space wrap>
                  {canReview && (
                    <Segmented
                      value={scope}
                      onChange={(value) => setScope(value as 'mine' | 'all')}
                      options={[
                        { label: '我的建议', value: 'mine' },
                        { label: '全部建议', value: 'all' },
                      ]}
                    />
                  )}
                  {canCreate && (
                    <Button type="primary" onClick={() => setSuggestionOpen(true)}>
                      发起建议
                    </Button>
                  )}
                </Space>
              }
            >
              <Paragraph type="secondary">
                建议从草稿进入沟通与受理；受理时必须指定配置负责人，最终启用由租户级复核人批准。
                非法状态流转会被后端拒绝。
              </Paragraph>
              {actionError && !detailOpen && (
                <Alert type="error" showIcon message={actionError} style={{ marginBottom: 12 }} />
              )}
              <AsyncState
                phase={suggestionPhase}
                emptyText="还没有模块建议。"
                errorTitle="模块建议加载失败"
                {...(suggestionError ? { errorMessage: suggestionError } : {})}
                onRetry={loadSuggestions}
              >
                <Table<ModuleSuggestion>
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={suggestions}
                  columns={[
                    {
                      title: '建议',
                      dataIndex: 'title',
                      render: (_value, record) => (
                        <Space direction="vertical" size={0}>
                          <Text strong>{record.title}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {record.businessProblem}
                          </Text>
                        </Space>
                      ),
                    },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 140,
                      render: (value: ModuleSuggestionStatus) => (
                        <Tag color={STATUS_COLOR[value] ?? 'default'}>
                          {STATUS_LABEL[value] ?? value}
                        </Tag>
                      ),
                    },
                    {
                      title: '发起人',
                      dataIndex: 'requesterPersonId',
                      width: 120,
                      render: (value: string) => shortId(value),
                    },
                    {
                      title: '更新时间',
                      dataIndex: 'updatedAt',
                      width: 170,
                      render: (value: string) => formatDateTime(value),
                    },
                    {
                      title: '操作',
                      key: 'actions',
                      width: 90,
                      render: (_value, record) => (
                        <Button size="small" onClick={() => openDetail(record.id)}>
                          查看
                        </Button>
                      ),
                    },
                  ]}
                />
              </AsyncState>
            </Card>
          ),
        },
      ]}
    />

      {/* 新增模块 */}
    <Modal
      title="登记模块"
      open={moduleOpen}
      onCancel={() => setModuleOpen(false)}
      confirmLoading={savingModule}
      okText="保存"
      cancelText="取消"
      onOk={() => {
        void moduleForm.validateFields().then((values) => submitModule(values));
      }}
    >
      <Form form={moduleForm} layout="vertical" initialValues={{ version: '1.0.0' }}>
        <Form.Item name="code" label="模块编码" rules={[{ required: true, message: '请输入模块编码' }]}>
          <Input placeholder="例如 hr.attendance" />
        </Form.Item>
        <Form.Item name="name" label="模块名称" rules={[{ required: true, message: '请输入模块名称' }]}>
          <Input />
        </Form.Item>
        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="category" label="分类" style={{ width: 180 }} initialValue="通用">
            <Input />
          </Form.Item>
          <Form.Item name="version" label="版本" style={{ width: 140 }}>
            <Input />
          </Form.Item>
          <Form.Item name="applicableScope" label="适用范围" style={{ width: 160 }} initialValue="tenant">
            <Select
              options={Object.entries(SCOPE_LABEL).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
        </Space>
        <Form.Item name="description" label="说明">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="healthStatus" label="健康状态" style={{ width: 180 }} initialValue="unknown">
            <Select
              options={Object.entries(HEALTH_LABEL).map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          <Form.Item name="ownerPersonId" label="负责人 ID" style={{ width: 300 }}>
            <Input placeholder="员工 UUID，可留空" />
          </Form.Item>
        </Space>
        <Form.Item name="dependencies" label="依赖（JSON 数组）" initialValue="[]">
          <Input.TextArea rows={2} placeholder='["org.structure"]' />
        </Form.Item>
      </Form>
    </Modal>

      {/* 发起建议 */}
    <Modal
      title="发起模块建议"
      open={suggestionOpen}
      onCancel={() => setSuggestionOpen(false)}
      confirmLoading={savingSuggestion}
      okText="提交"
      cancelText="取消"
      width={640}
      onOk={() => {
        void suggestionForm.validateFields().then((values) => submitSuggestion(values));
      }}
    >
      <Form form={suggestionForm} layout="vertical">
        <Form.Item name="title" label="建议标题" rules={[{ required: true, message: '请输入标题' }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="businessProblem"
          label="业务问题"
          rules={[{ required: true, message: '请描述要解决的业务问题' }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="goal" label="目标" style={{ width: 290 }}>
            <Input />
          </Form.Item>
          <Form.Item name="scope" label="范围" style={{ width: 290 }}>
            <Input />
          </Form.Item>
        </Space>
        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="dataScope" label="涉及数据" style={{ width: 290 }}>
            <Input />
          </Form.Item>
          <Form.Item name="permissionsNeeded" label="所需权限" style={{ width: 290 }}>
            <Input />
          </Form.Item>
        </Space>
        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="costEstimate" label="成本预估" style={{ width: 290 }}>
            <Input />
          </Form.Item>
          <Form.Item name="benefitEstimate" label="收益预估" style={{ width: 290 }}>
            <Input />
          </Form.Item>
        </Space>
        <Form.Item name="suggestedOwnerPersonId" label="负责人建议（员工 UUID，可留空）">
          <Input />
        </Form.Item>
      </Form>
    </Modal>

      {/* 建议详情 */}
    <Drawer
      title={detail ? detail.suggestion.title : '模块建议'}
      width={560}
      open={detailOpen}
      onClose={() => {
        setDetailOpen(false);
        setDetail(null);
        setPendingAction(null);
        setActionError(null);
      }}
    >
      {detailLoading && <AsyncState phase="loading" loadingText="正在读取建议详情…" />}
      {!detailLoading && !detail && actionError && (
        <Alert type="error" showIcon message={actionError} />
      )}
      {detail && (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="状态">
              <Tag color={STATUS_COLOR[detail.suggestion.status] ?? 'default'}>
                {STATUS_LABEL[detail.suggestion.status] ?? detail.suggestion.status}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="业务问题">
              {detail.suggestion.businessProblem}
            </Descriptions.Item>
            <Descriptions.Item label="目标">{detail.suggestion.goal || '—'}</Descriptions.Item>
            <Descriptions.Item label="范围">{detail.suggestion.scope || '—'}</Descriptions.Item>
            <Descriptions.Item label="涉及数据">
              {detail.suggestion.dataScope || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="所需权限">
              {detail.suggestion.permissionsNeeded || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="成本 / 收益">
              {(detail.suggestion.costEstimate || '—')}
              {' / '}
              {(detail.suggestion.benefitEstimate || '—')}
            </Descriptions.Item>
            <Descriptions.Item label="配置负责人">
              {detail.suggestion.configOwnerPersonId
                ? shortId(detail.suggestion.configOwnerPersonId)
                : '—'}
            </Descriptions.Item>
          </Descriptions>

          {actionError && <Alert type="error" showIcon message={actionError} />}

          <div>
            <Text strong>处理记录</Text>
            <Timeline
              style={{ marginTop: 12 }}
              items={detail.events.map((event) => ({
                color: event.eventKind === 'notification' ? 'blue' : 'gray',
                children: (
                  <Space direction="vertical" size={0}>
                    <Text>
                      {ACTIONS[event.action]?.label ?? event.action}
                      {event.fromStatus && event.toStatus && event.fromStatus !== event.toStatus
                        ? ` · ${STATUS_LABEL[event.fromStatus as ModuleSuggestionStatus] ?? event.fromStatus} → ${STATUS_LABEL[event.toStatus as ModuleSuggestionStatus] ?? event.toStatus}`
                        : ''}
                    </Text>
                    {event.comment && <Text type="secondary">{event.comment}</Text>}
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {event.actorPersonId ? shortId(event.actorPersonId) : '系统'} ·{' '}
                      {formatDateTime(event.occurredAt)}
                    </Text>
                  </Space>
                ),
              }))}
            />
          </div>

          {availableActions.length > 0 && (
            <Space wrap>
              {availableActions.map((action) => (
                <Button
                  key={action}
                  type={action === 'approve' || action === 'accept' ? 'primary' : 'default'}
                  danger={action === 'reject' || action === 'test-fail'}
                  onClick={() => {
                    setActionError(null);
                    setPendingAction(action);
                    actionForm.resetFields();
                  }}
                >
                  {ACTIONS[action]?.label ?? action}
                </Button>
              ))}
            </Space>
          )}
        </Space>
      )}
    </Drawer>

      {/* 流转动作 */}
    <Modal
      title={pendingDefinition?.label ?? '状态流转'}
      open={pendingAction !== null}
      onCancel={() => {
        setPendingAction(null);
        actionForm.resetFields();
      }}
      confirmLoading={acting}
      okText="确认"
      cancelText="取消"
      onOk={() => {
        if (!pendingAction) {
          return;
        }
        void actionForm
          .validateFields()
          .then((values) =>
            runAction(
              pendingAction,
              values.comment ?? '',
              values.configOwnerPersonId as string | undefined,
            ),
          );
      }}
    >
      <Form form={actionForm} layout="vertical">
        {pendingDefinition?.needsComment && (
          <Form.Item
            name="comment"
            label="意见"
            rules={[{ required: true, message: '请填写意见' }]}
          >
            <Input.TextArea rows={3} />
          </Form.Item>
        )}
        {pendingDefinition?.needsOwner && (
          <Form.Item
            name="configOwnerPersonId"
            label="配置负责人（员工 UUID）"
            rules={[{ required: true, message: '受理时必须指定配置负责人' }]}
          >
            <Input />
          </Form.Item>
        )}
        {!pendingDefinition?.needsComment && !pendingDefinition?.needsOwner && (
          <Paragraph type="secondary">确认执行「{pendingDefinition?.label}」？</Paragraph>
        )}
      </Form>
    </Modal>
    </>
  );
}
