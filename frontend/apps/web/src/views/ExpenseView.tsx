import type {
  ApiClient,
  ExpenseAuditEvent,
  ExpenseBatchHold,
  ExpenseBatchScope,
  ExpenseDocType,
  ExpenseDocument,
  ExpenseDocumentView,
  ExpenseManualFields,
  ExpensePolicy,
  ExpenseStatus,
} from '@platform/api-client';
import { tokens } from '@platform/design-system';
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Modal,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from 'react';
import { AsyncState } from '../components/AsyncState';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

const STATUS_LABEL: Record<ExpenseStatus, string> = {
  manual_review: '待人工复核',
  approved: '已通过待入账',
  posted: '已入账（待对接账务）',
  rejected: '已驳回',
  voided: '已撤销',
};

const STATUS_COLOR: Record<ExpenseStatus, string> = {
  manual_review: 'orange',
  approved: 'blue',
  posted: 'green',
  rejected: 'red',
  voided: 'default',
};

const DOC_TYPE_LABEL: Record<ExpenseDocType, string> = {
  invoice: '发票',
  receipt: '收据',
  bill: '账单',
};

const EXTRACTION_LABEL: Record<string, string> = {
  OK: '自动抽取成功',
  MANUAL: '手工填写',
  UNAVAILABLE: 'OCR 未配置或不可达，已转人工',
  TIMEOUT: 'OCR 超时，已转人工',
  INVALID: 'OCR 输出无法解析，已转人工',
  PDF_UNSUPPORTED: 'PDF 不做自动 OCR，已转人工',
};

const ACTION_LABEL: Record<string, string> = {
  submitted: '提交单据',
  'pre_review.completed': 'AI 预审完成',
  'review.decided': '人工复核',
  auto_posted: 'AI 预审通过并入账',
  posted: '人工入账',
  voided: '撤销入账',
  'policy.updated': '更新租户策略',
  'batch_anomaly.detected': '检测到批量异常',
  'batch_hold.released': '解除批次暂停',
};

const SCOPE_LABEL: Record<ExpenseBatchScope, string> = {
  vendor: '同一供应商',
  uploader: '同一上传人',
  amount: '金额集中',
};

export interface ExpenseViewProps {
  apiClient: ApiClient;
  permissions?: Set<string> | null;
}

function allowed(permissions: Set<string> | null | undefined, code: string): boolean {
  return permissions == null || permissions.has(code);
}

function amountText(document: ExpenseDocument): string {
  if (document.amount == null) {
    return '—';
  }
  return `${document.currency ?? 'CNY'} ${document.amount.toFixed(2)}`;
}

function blockingCount(document: ExpenseDocument): number {
  return document.findings.filter((finding) => finding.status === 'fail'
    || finding.status === 'blocked').length;
}

function splitList(value: string): string[] {
  return value
    .split(/[,，\n]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function ExpenseView({ apiClient, permissions = null }: ExpenseViewProps) {
  const [documents, setDocuments] = useState<ExpenseDocumentView[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [docType, setDocType] = useState<ExpenseDocType>('invoice');
  const [submitting, setSubmitting] = useState(false);
  const [manual, setManual] = useState({
    amount: '',
    invoiceNo: '',
    vendorName: '',
    vendorTaxNo: '',
    occurredOn: '',
    category: '',
    title: '',
  });
  const [detail, setDetail] = useState<ExpenseDocumentView | null>(null);
  const [audit, setAudit] = useState<ExpenseAuditEvent[] | null>(null);
  const [reviewComment, setReviewComment] = useState('');
  const [voidReason, setVoidReason] = useState('');
  const [policy, setPolicy] = useState<ExpensePolicy | null>(null);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [policySaving, setPolicySaving] = useState(false);
  const [holds, setHolds] = useState<ExpenseBatchHold[] | null>(null);
  const [holdsError, setHoldsError] = useState<string | null>(null);
  const [holdNotes, setHoldNotes] = useState<Record<string, string>>({});
  const [releasingId, setReleasingId] = useState<string | null>(null);

  const canReview = allowed(permissions, 'expense.document.review');
  const canPost = allowed(permissions, 'expense.document.post');
  const canVoid = allowed(permissions, 'expense.document.void');
  const canManagePolicy = allowed(permissions, 'expense.policy.manage');

  const refresh = useCallback(() => {
    return apiClient
      .listExpenseDocuments({ limit: 100 })
      .then((list) => {
        setDocuments(list);
        setLoadError(null);
      })
      .catch((error: unknown) => setLoadError(describeError(error)));
  }, [apiClient]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!canManagePolicy) {
      return;
    }
    apiClient
      .getExpensePolicy()
      .then((value) => {
        setPolicy(value);
        setPolicyError(null);
      })
      .catch((error: unknown) => setPolicyError(describeError(error)));
  }, [apiClient, canManagePolicy]);

  const refreshHolds = useCallback(() => {
    if (!canReview) {
      return Promise.resolve();
    }
    return apiClient
      .listExpenseHolds('active')
      .then((list) => {
        setHolds(list);
        setHoldsError(null);
      })
      .catch((error: unknown) => setHoldsError(describeError(error)));
  }, [apiClient, canReview]);

  useEffect(() => {
    void refreshHolds();
  }, [refreshHolds]);

  const detailDocument = detail?.document ?? null;
  const detailFindings = useMemo(
    () => detailDocument?.findings ?? [],
    [detailDocument],
  );

  function handleSelect(event: ChangeEvent<HTMLInputElement>): void {
    setSelectedFile(event.target.files?.[0] ?? null);
    setActionError(null);
    setNotice(null);
  }

  async function handleSubmit(): Promise<void> {
    if (!selectedFile) {
      setActionError('请先选择要提交的凭证文件。');
      return;
    }
    setSubmitting(true);
    setActionError(null);
    setNotice(null);
    try {
      const contentType = selectedFile.type || 'application/octet-stream';
      const session = await apiClient.createUploadSession({
        fileName: selectedFile.name,
        contentType,
        sizeBytes: selectedFile.size,
      });
      const body = await selectedFile.arrayBuffer();
      const putResponse = await fetch(session.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': contentType },
        body,
      });
      if (!putResponse.ok) {
        throw new Error(`对象存储上传失败：HTTP ${putResponse.status}`);
      }
      await apiClient.completeUpload(session.fileId);

      const fields: ExpenseManualFields = {};
      if (manual.amount.trim()) {
        fields.amount = Number(manual.amount);
      }
      if (manual.invoiceNo.trim()) {
        fields.invoiceNo = manual.invoiceNo.trim();
      }
      if (manual.vendorName.trim()) {
        fields.vendorName = manual.vendorName.trim();
      }
      if (manual.vendorTaxNo.trim()) {
        fields.vendorTaxNo = manual.vendorTaxNo.trim();
      }
      if (manual.occurredOn) {
        fields.occurredOn = manual.occurredOn;
      }
      if (manual.category.trim()) {
        fields.category = manual.category.trim();
      }
      if (manual.title.trim()) {
        fields.title = manual.title.trim();
      }
      const view = await apiClient.createExpenseDocument({
        fileId: session.fileId,
        docType,
        ...(Object.keys(fields).length > 0 ? { fields } : {}),
      });
      const conclusion = view.document.status === 'posted'
        ? '全部规则通过，已自动入账（待对接账务）。'
        : `存在 ${blockingCount(view.document)} 条未通过规则，已转人工复核。`;
      setNotice(conclusion);
      setSelectedFile(null);
      setManual({
        amount: '', invoiceNo: '', vendorName: '', vendorTaxNo: '',
        occurredOn: '', category: '', title: '',
      });
      await refresh();
      await openDetail(view);
    } catch (error) {
      setActionError(describeError(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function openDetail(view: ExpenseDocumentView): Promise<void> {
    setDetail(view);
    setAudit(null);
    setDetailError(null);
    setReviewComment('');
    setVoidReason('');
    try {
      const events = await apiClient.listExpenseDocumentAudit(view.document.id);
      setAudit(events);
    } catch {
      setAudit([]);
    }
  }

  async function applyReview(decision: 'approve' | 'reject'): Promise<void> {
    if (!detail) {
      return;
    }
    if (decision === 'reject' && !reviewComment.trim()) {
      setDetailError('驳回必须填写原因。');
      return;
    }
    setDetailError(null);
    try {
      const updated = await apiClient.reviewExpenseDocument(detail.document.id, {
        decision,
        comment: reviewComment.trim(),
      });
      setDetail(updated);
      message.success(decision === 'approve' ? '已通过，等待入账。' : '已驳回并释放发票号。');
      await refresh();
      await openDetail(updated);
    } catch (error) {
      setDetailError(describeError(error));
    }
  }

  async function applyPost(): Promise<void> {
    if (!detail) {
      return;
    }
    setDetailError(null);
    try {
      const updated = await apiClient.postExpenseDocument(detail.document.id);
      setDetail(updated);
      message.success('已入账（待对接账务）。');
      await refresh();
      await openDetail(updated);
    } catch (error) {
      setDetailError(describeError(error));
    }
  }

  async function applyVoid(): Promise<void> {
    if (!detail) {
      return;
    }
    if (!voidReason.trim()) {
      setDetailError('撤销必须填写原因。');
      return;
    }
    setDetailError(null);
    try {
      const updated = await apiClient.voidExpenseDocument(detail.document.id, {
        reason: voidReason.trim(),
      });
      setDetail(updated);
      message.success('已撤销，发票号已释放并留痕。');
      await refresh();
      await openDetail(updated);
    } catch (error) {
      setDetailError(describeError(error));
    }
  }

  async function savePolicy(): Promise<void> {
    if (!policy) {
      return;
    }
    setPolicySaving(true);
    setPolicyError(null);
    try {
      const updated = await apiClient.updateExpensePolicy({
        autoPostEnabled: policy.autoPostEnabled,
        autoPostLimit: policy.autoPostLimit,
        windowDays: policy.windowDays,
        legalTitle: policy.legalTitle ?? '',
        taxId: policy.taxId ?? '',
        vendorAllowlist: policy.vendorAllowlist,
        manualCategories: policy.manualCategories,
        allowedCategories: policy.allowedCategories,
        minConfidence: policy.minConfidence,
        verifyRequired: policy.verifyRequired,
      });
      setPolicy(updated);
      message.success(`策略已更新到 v${updated.version}，变更已写入审计。`);
    } catch (error) {
      setPolicyError(describeError(error));
    } finally {
      setPolicySaving(false);
    }
  }

  async function releaseHold(holdId: string): Promise<void> {
    const note = (holdNotes[holdId] ?? '').trim();
    if (!note) {
      setHoldsError('解除暂停必须填写说明。');
      return;
    }
    setReleasingId(holdId);
    setHoldsError(null);
    try {
      await apiClient.releaseExpenseHold(holdId, { note });
      message.success('已解除该批次暂停，自动入账判定恢复。');
      setHoldNotes((current) => {
        const next = { ...current };
        delete next[holdId];
        return next;
      });
      await refreshHolds();
    } catch (error) {
      setHoldsError(describeError(error));
    } finally {
      setReleasingId(null);
    }
  }

  const documentsTab = (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="上传凭证并预审">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            先直传对象存储并完成安全扫描，再提交预审。全部规则通过会自动入账；
            任何异常都会转人工，并逐条列出未通过规则与置信度。
          </Paragraph>
          {actionError && <Alert type="error" showIcon message={actionError} />}
          {notice && <Alert type="success" showIcon message={notice} />}
          {selectedFile?.type === 'application/pdf' && (
            <Alert
              type="warning"
              showIcon
              message="PDF 不做自动 OCR"
              description="请在下方手工填写金额、发票号等字段后一起提交；手工字段同样会跑全部规则。"
            />
          )}
          <Space wrap align="end">
            <label htmlFor="expense-file-input">
              <Text strong>选择凭证文件</Text>
              <input
                id="expense-file-input"
                type="file"
                accept="image/png,image/jpeg,image/webp,application/pdf"
                onChange={handleSelect}
                style={{ display: 'block', marginTop: tokens.spacing.xs }}
              />
            </label>
            <label htmlFor="expense-doc-type">
              <Text strong>单据类型</Text>
              <select
                id="expense-doc-type"
                value={docType}
                onChange={(event) => setDocType(event.target.value as ExpenseDocType)}
                style={{ display: 'block', marginTop: tokens.spacing.xs, height: 32 }}
              >
                <option value="invoice">发票</option>
                <option value="receipt">收据</option>
                <option value="bill">账单</option>
              </select>
            </label>
            <Button type="primary" loading={submitting} onClick={() => void handleSubmit()}>
              上传并提交预审
            </Button>
          </Space>

          <Card size="small" title="手工字段（可选，PDF 建议填写）">
            <Space wrap>
              <label htmlFor="expense-amount">
                <Text type="secondary">金额</Text>
                <Input
                  id="expense-amount"
                  value={manual.amount}
                  onChange={(event) => setManual({ ...manual, amount: event.target.value })}
                  style={{ width: 130 }}
                />
              </label>
              <label htmlFor="expense-invoice-no">
                <Text type="secondary">发票号</Text>
                <Input
                  id="expense-invoice-no"
                  value={manual.invoiceNo}
                  onChange={(event) => setManual({ ...manual, invoiceNo: event.target.value })}
                  style={{ width: 170 }}
                />
              </label>
              <label htmlFor="expense-vendor">
                <Text type="secondary">供应商</Text>
                <Input
                  id="expense-vendor"
                  value={manual.vendorName}
                  onChange={(event) => setManual({ ...manual, vendorName: event.target.value })}
                  style={{ width: 160 }}
                />
              </label>
              <label htmlFor="expense-vendor-tax">
                <Text type="secondary">供应商税号</Text>
                <Input
                  id="expense-vendor-tax"
                  value={manual.vendorTaxNo}
                  onChange={(event) => setManual({ ...manual, vendorTaxNo: event.target.value })}
                  style={{ width: 180 }}
                />
              </label>
              <label htmlFor="expense-date">
                <Text type="secondary">发生日期</Text>
                <Input
                  id="expense-date"
                  type="date"
                  value={manual.occurredOn}
                  onChange={(event) => setManual({ ...manual, occurredOn: event.target.value })}
                  style={{ width: 150 }}
                />
              </label>
              <label htmlFor="expense-category">
                <Text type="secondary">科目</Text>
                <Input
                  id="expense-category"
                  value={manual.category}
                  onChange={(event) => setManual({ ...manual, category: event.target.value })}
                  style={{ width: 130 }}
                />
              </label>
              <label htmlFor="expense-title">
                <Text type="secondary">抬头</Text>
                <Input
                  id="expense-title"
                  value={manual.title}
                  onChange={(event) => setManual({ ...manual, title: event.target.value })}
                  style={{ width: 190 }}
                />
              </label>
            </Space>
          </Card>
        </Space>
      </Card>

      <Card title="单据列表">
        {loadError ? (
          <AsyncState
            phase="error"
            errorTitle="单据列表加载失败"
            errorMessage={loadError}
            onRetry={() => void refresh()}
          />
        ) : documents === null ? (
          <AsyncState phase="loading" loadingText="正在读取单据…" />
        ) : documents.length === 0 ? (
          <AsyncState phase="empty" emptyText="还没有单据，先上传一张发票或账单。" />
        ) : (
          <Table<ExpenseDocumentView>
            size="small"
            rowKey={(row) => row.document.id}
            pagination={false}
            dataSource={documents}
            columns={[
              {
                title: '单据',
                key: 'document',
                render: (_value, row) => (
                  <Space direction="vertical" size={0}>
                    <Text>{DOC_TYPE_LABEL[row.document.docType]} · {amountText(row.document)}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {row.document.invoiceNo ?? '无发票号'} · {shortId(row.document.id, 10)}
                    </Text>
                  </Space>
                ),
              },
              {
                title: '状态',
                key: 'status',
                render: (_value, row) => (
                  <Tag color={STATUS_COLOR[row.document.status]}>
                    {STATUS_LABEL[row.document.status]}
                  </Tag>
                ),
              },
              {
                title: '预审结论',
                key: 'findings',
                render: (_value, row) => {
                  const blocking = blockingCount(row.document);
                  return blocking === 0
                    ? <Tag color="green">规则全部通过</Tag>
                    : <Tag color="orange">{blocking} 条待人工确认</Tag>;
                },
              },
              {
                title: '创建时间',
                key: 'createdAt',
                render: (_value, row) => formatDateTime(row.document.createdAt),
              },
              {
                title: '操作',
                key: 'actions',
                render: (_value, row) => (
                  <Button size="small" onClick={() => void openDetail(row)}>
                    查看详情
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Card>
    </Space>
  );

  const holdsTab = (
    <Card title="批量异常告警">
      <Paragraph type="secondary">
        同一供应商 24 小时内提交 ≥5 张、同一上传人 ≥10 张或金额相近（±5%）≥5 张时，
        系统会自动暂停该批次的自动入账并转人工。解除暂停需要 expense.document.post 权限，
        且必须填写说明，操作会写入审计。
      </Paragraph>
      {holdsError && <Alert type="error" showIcon message={holdsError} />}
      {holds === null ? (
        <AsyncState phase="loading" loadingText="正在读取异常告警…" />
      ) : holds.length === 0 ? (
        <AsyncState phase="empty" emptyText="当前没有暂停中的批次。" />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="warning" showIcon message={`${holds.length} 个批次已暂停自动入账`} />
          <Table<ExpenseBatchHold>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={holds}
            columns={[
              {
                title: '范围',
                dataIndex: 'scopeType',
                width: 110,
                render: (value: ExpenseBatchScope) => SCOPE_LABEL[value] ?? value,
              },
              { title: '对象', dataIndex: 'scopeKey' },
              { title: '原因', dataIndex: 'reason' },
              { title: '单据数', dataIndex: 'documentCount', width: 90 },
              {
                title: '触发时间',
                dataIndex: 'createdAt',
                width: 180,
                render: (value: string) => formatDateTime(value),
              },
              ...(canPost ? [{
                title: '操作',
                key: 'actions',
                render: (_value: unknown, row: ExpenseBatchHold) => (
                  <Space.Compact>
                    <Input
                      aria-label={`解除说明-${row.id}`}
                      placeholder="解除说明"
                      value={holdNotes[row.id] ?? ''}
                      onChange={(event) => setHoldNotes({
                        ...holdNotes,
                        [row.id]: event.target.value,
                      })}
                    />
                    <Button
                      loading={releasingId === row.id}
                      onClick={() => void releaseHold(row.id)}
                    >
                      解除
                    </Button>
                  </Space.Compact>
                ),
              }] : []),
            ]}
          />
        </Space>
      )}
    </Card>
  );

  const policyTab = (
    <Card title="租户预审策略">
      <Paragraph type="secondary">
        策略变更需要 expense.policy.manage，并把变更前后值写入审计。
        关闭发票验真只适用于演示或已线下核验的场景。
      </Paragraph>
      {policyError && <Alert type="error" showIcon message={policyError} />}
      {!policy ? (
        <AsyncState phase={policyError ? 'error' : 'loading'} loadingText="正在读取策略…" />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <label>
              <Text strong>允许自动入账</Text>
              <div>
                <Switch
                  checked={policy.autoPostEnabled}
                  onChange={(checked) => setPolicy({ ...policy, autoPostEnabled: checked })}
                />
              </div>
            </label>
            <label>
              <Text strong>自动入账上限</Text>
              <div>
                <InputNumber
                  value={policy.autoPostLimit}
                  min={0}
                  onChange={(value) => setPolicy({
                    ...policy,
                    autoPostLimit: typeof value === 'number' ? value : policy.autoPostLimit,
                  })}
                />
              </div>
            </label>
            <label>
              <Text strong>报销窗口（天）</Text>
              <div>
                <InputNumber
                  value={policy.windowDays}
                  min={1}
                  max={3650}
                  onChange={(value) => setPolicy({
                    ...policy,
                    windowDays: typeof value === 'number' ? value : policy.windowDays,
                  })}
                />
              </div>
            </label>
            <label>
              <Text strong>置信度门槛</Text>
              <div>
                <InputNumber
                  value={policy.minConfidence}
                  min={0}
                  max={1}
                  step={0.05}
                  onChange={(value) => setPolicy({
                    ...policy,
                    minConfidence: typeof value === 'number' ? value : policy.minConfidence,
                  })}
                />
              </div>
            </label>
            <label>
              <Text strong>强制发票验真</Text>
              <div>
                <Switch
                  checked={policy.verifyRequired}
                  onChange={(checked) => setPolicy({ ...policy, verifyRequired: checked })}
                />
              </div>
            </label>
          </Space>
          <Space wrap>
            <label htmlFor="expense-legal-title">
              <Text type="secondary">法定抬头</Text>
              <Input
                id="expense-legal-title"
                value={policy.legalTitle ?? ''}
                onChange={(event) => setPolicy({ ...policy, legalTitle: event.target.value })}
                style={{ width: 220 }}
              />
            </label>
            <label htmlFor="expense-tax-id">
              <Text type="secondary">租户税号</Text>
              <Input
                id="expense-tax-id"
                value={policy.taxId ?? ''}
                onChange={(event) => setPolicy({ ...policy, taxId: event.target.value })}
                style={{ width: 220 }}
              />
            </label>
          </Space>
          <label htmlFor="expense-vendors">
            <Text type="secondary">供应商白名单（逗号或换行分隔）</Text>
            <Input.TextArea
              id="expense-vendors"
              rows={2}
              value={policy.vendorAllowlist.join('\n')}
              onChange={(event) => setPolicy({
                ...policy,
                vendorAllowlist: splitList(event.target.value),
              })}
            />
          </label>
          <label htmlFor="expense-manual-categories">
            <Text type="secondary">必须人工复核的科目</Text>
            <Input.TextArea
              id="expense-manual-categories"
              rows={2}
              value={policy.manualCategories.join('\n')}
              onChange={(event) => setPolicy({
                ...policy,
                manualCategories: splitList(event.target.value),
              })}
            />
          </label>
          <label htmlFor="expense-allowed-categories">
            <Text type="secondary">允许科目（留空表示不限制）</Text>
            <Input.TextArea
              id="expense-allowed-categories"
              rows={2}
              value={policy.allowedCategories.join('\n')}
              onChange={(event) => setPolicy({
                ...policy,
                allowedCategories: splitList(event.target.value),
              })}
            />
          </label>
          <Space>
            <Button type="primary" loading={policySaving} onClick={() => void savePolicy()}>
              保存策略
            </Button>
            <Text type="secondary">当前版本 v{policy.version}</Text>
          </Space>
        </Space>
      )}
    </Card>
  );

  return (
    <>
      <Tabs
        items={[
          { key: 'documents', label: '我的单据', children: documentsTab },
          ...(canReview ? [{ key: 'holds', label: '异常告警', children: holdsTab }] : []),
          ...(canManagePolicy ? [{ key: 'policy', label: '租户策略', children: policyTab }] : []),
        ]}
      />

      <Modal
        open={detail !== null}
        title="单据详情"
        width={860}
        footer={null}
        onCancel={() => setDetail(null)}
      >
        {detailDocument && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            {detailError && <Alert type="error" showIcon message={detailError} />}
            <Space wrap>
              <Tag color={STATUS_COLOR[detailDocument.status]}>
                {STATUS_LABEL[detailDocument.status]}
              </Tag>
              <Text>{DOC_TYPE_LABEL[detailDocument.docType]} · {amountText(detailDocument)}</Text>
              <Text type="secondary">
                规则 {detailDocument.ruleVersion} · 策略 v{detailDocument.policyVersion}
              </Text>
              <Button
                size="small"
                onClick={() => {
                  void apiClient.getDownloadUrl(detailDocument.fileId)
                    .then((grant) => globalThis.open(grant.url, '_blank', 'noopener'))
                    .catch((error: unknown) => setDetailError(describeError(error)));
                }}
              >
                下载原始凭证
              </Button>
            </Space>

            {detail?.extraction && (
              <Alert
                type={detail.extraction.status === 'OK' || detail.extraction.status === 'MANUAL'
                  ? 'info' : 'warning'}
                showIcon
                message={EXTRACTION_LABEL[detail.extraction.status]
                  ?? `抽取状态：${detail.extraction.status}`}
                {...(detail.extraction.errorCode
                  ? { description: `原因码：${detail.extraction.errorCode}` }
                  : {})}
              />
            )}

            <Space wrap>
              <Text type="secondary">供应商：{detailDocument.vendorName ?? '—'}</Text>
              <Text type="secondary">税号：{detailDocument.vendorTaxNo ?? '—'}</Text>
              <Text type="secondary">发票号：{detailDocument.invoiceNo ?? '—'}</Text>
              <Text type="secondary">发生日期：{detailDocument.occurredOn ?? '—'}</Text>
              <Text type="secondary">科目：{detailDocument.category ?? '—'}</Text>
            </Space>

            <Card size="small" title="逐条规则">
              <Table
                size="small"
                rowKey="code"
                pagination={false}
                dataSource={detailFindings}
                columns={[
                  { title: '规则', dataIndex: 'code', width: 80 },
                  {
                    title: '结论',
                    dataIndex: 'status',
                    width: 90,
                    render: (value: string) => (
                      <Tag color={value === 'pass' ? 'green'
                        : value === 'skipped' ? 'default' : 'red'}>
                        {value}
                      </Tag>
                    ),
                  },
                  { title: '说明', dataIndex: 'message' },
                  {
                    title: '实际 / 期望',
                    key: 'values',
                    render: (_value, row) => (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {row.actual ?? '—'} / {row.expected ?? '—'}
                      </Text>
                    ),
                  },
                ]}
              />
            </Card>

            <Card size="small" title="审计时间线">
              {audit === null ? (
                <AsyncState phase="loading" loadingText="正在读取审计…" />
              ) : audit.length === 0 ? (
                <AsyncState phase="empty" emptyText="暂无审计记录。" />
              ) : (
                <Timeline
                  items={audit.map((event) => ({
                    key: event.id,
                    color: event.action === 'voided' ? 'red' : 'blue',
                    children: (
                      <Space direction="vertical" size={0}>
                        <Text>{ACTION_LABEL[event.action] ?? event.action}</Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {formatDateTime(event.occurredAt)} · {event.actorKind}
                        </Text>
                      </Space>
                    ),
                  }))}
                />
              )}
            </Card>

            {detailDocument.status === 'manual_review' && canReview && (
              <Card size="small" title="人工复核">
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  <Input.TextArea
                    aria-label="复核意见"
                    rows={2}
                    placeholder="驳回必须填写原因；通过可留空"
                    value={reviewComment}
                    onChange={(event) => setReviewComment(event.target.value)}
                  />
                  <Space>
                    <Button type="primary" onClick={() => void applyReview('approve')}>
                      复核通过
                    </Button>
                    <Button danger onClick={() => void applyReview('reject')}>
                      驳回
                    </Button>
                  </Space>
                </Space>
              </Card>
            )}

            {detailDocument.status === 'approved' && canPost && (
              <Button type="primary" onClick={() => void applyPost()}>
                入账
              </Button>
            )}

            {detailDocument.status === 'posted' && canVoid && (
              <Card size="small" title="撤销入账">
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  <Input
                    aria-label="撤销原因"
                    placeholder="必须填写撤销原因"
                    value={voidReason}
                    onChange={(event) => setVoidReason(event.target.value)}
                  />
                  <Button danger onClick={() => void applyVoid()}>
                    撤销
                  </Button>
                </Space>
              </Card>
            )}
          </Space>
        )}
      </Modal>
    </>
  );
}
