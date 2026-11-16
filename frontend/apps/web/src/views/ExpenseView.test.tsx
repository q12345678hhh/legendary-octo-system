import type {
  ApiClient,
  ExpenseAuditEvent,
  ExpenseBatchHold,
  ExpenseDocument,
  ExpenseDocumentView,
  ExpensePolicy,
} from '@platform/api-client';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ExpenseView } from './ExpenseView';

function documentFixture(overrides: Partial<ExpenseDocument> = {}): ExpenseDocument {
  return {
    id: 'd1',
    uploaderPersonId: 'p1',
    fileId: 'f1',
    docType: 'invoice',
    status: 'manual_review',
    currency: 'CNY',
    amount: 128.5,
    invoiceCode: '044',
    invoiceNo: '88000011',
    vendorName: '云图科技',
    vendorTaxNo: '91310000XYZ',
    occurredOn: '2026-11-10',
    category: '差旅',
    findings: [
      { code: 'R001', status: 'pass', message: '必填字段齐全' },
      { code: 'R012', status: 'blocked', message: '发票验真服务未接入，按策略必须人工复核' },
    ],
    confidence: { amount: 0.98 },
    ruleVersion: 'expense-rules-2026.11',
    policyVersion: 3,
    extractionId: 'x1',
    postedAt: null,
    postedBy: null,
    postActorKind: null,
    voidedAt: null,
    voidedBy: null,
    voidReason: null,
    createdAt: '2026-11-16T02:00:00Z',
    ...overrides,
  };
}

function viewFixture(overrides: Partial<ExpenseDocument> = {}): ExpenseDocumentView {
  return {
    document: documentFixture(overrides),
    extraction: {
      id: 'x1',
      status: 'UNAVAILABLE',
      provider: null,
      model: null,
      errorCode: 'OCR_NOT_CONFIGURED',
      ocrText: null,
      createdAt: '2026-11-16T02:00:00Z',
    },
  };
}

const auditFixture: ExpenseAuditEvent[] = [
  {
    id: 'a1', documentId: 'd1', action: 'submitted', actorPersonId: 'p1',
    actorKind: 'employee', detail: {}, occurredAt: '2026-11-16T02:00:00Z',
  },
  {
    id: 'a2', documentId: 'd1', action: 'pre_review.completed', actorPersonId: null,
    actorKind: 'ai', detail: {}, occurredAt: '2026-11-16T02:00:01Z',
  },
];

const policyFixture: ExpensePolicy = {
  autoPostEnabled: true,
  autoPostLimit: 5000,
  windowDays: 90,
  legalTitle: '上海云图科技有限公司',
  taxId: '91310000XYZ',
  vendorAllowlist: ['云图科技'],
  manualCategories: [],
  allowedCategories: ['差旅'],
  minConfidence: 0.7,
  verifyRequired: true,
  version: 3,
};

const holdFixture: ExpenseBatchHold = {
  id: 'h1',
  scopeType: 'vendor',
  scopeKey: '云图科技',
  reason: '24 小时内同一供应商提交 5 张单据，达到阈值 5 张',
  windowHours: 24,
  documentCount: 5,
  amountTotal: 640,
  status: 'active',
  createdBy: 'p1',
  createdAt: '2026-11-16T02:00:00Z',
  releasedBy: null,
  releasedAt: null,
  releaseNote: null,
};

// 宏任务轮询：避免 antd 动效下 RTL waitFor 的 act 无法结算。
async function flushUntil(check: () => boolean): Promise<void> {
  for (let i = 0; i < 200; i += 1) {
    if (check()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function client(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    listExpenseDocuments: vi.fn(async () => [viewFixture()]),
    listExpenseDocumentAudit: vi.fn(async () => auditFixture),
    createUploadSession: vi.fn(async () => ({
      fileId: 'f1', uploadUrl: 'http://upload.test/put', expiresAt: '2026-11-16T03:00:00Z',
    })),
    completeUpload: vi.fn(async () => ({
      fileId: 'f1', fileName: 'invoice.png', contentType: 'image/png', sizeBytes: 10,
      status: 'available', scanStatus: 'clean', scanDetail: null, uploadedAt: '2026-11-16T02:00:00Z',
    })),
    createExpenseDocument: vi.fn(async () => viewFixture()),
    getDownloadUrl: vi.fn(async () => ({
      url: 'http://download.test/f1', expiresAt: '2026-11-16T03:00:00Z',
    })),
    reviewExpenseDocument: vi.fn(async () => viewFixture({ status: 'approved' })),
    postExpenseDocument: vi.fn(async () => viewFixture({ status: 'posted' })),
    voidExpenseDocument: vi.fn(async () => viewFixture({ status: 'voided' })),
    getExpensePolicy: vi.fn(async () => policyFixture),
    updateExpensePolicy: vi.fn(async () => ({ ...policyFixture, version: 4 })),
    listExpenseHolds: vi.fn(async () => []),
    releaseExpenseHold: vi.fn(async () => ({ ...holdFixture, status: 'released' })),
    ...overrides,
  } as unknown as ApiClient;
}

const ALL_PERMISSIONS = new Set([
  'expense.document.read',
  'expense.document.submit',
  'expense.document.review',
  'expense.document.post',
  'expense.document.void',
  'expense.policy.manage',
]);

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200 })));
  // jsdom 未实现 File.arrayBuffer，上传链路在测试里用固定字节代替。
  Object.defineProperty(File.prototype, 'arrayBuffer', {
    value: async () => new Uint8Array([1, 2, 3]).buffer,
    configurable: true,
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe('ExpenseView', () => {
  it('renders OCR fallback and per-rule findings in detail', async () => {
    render(<ExpenseView apiClient={client()} permissions={ALL_PERMISSIONS} />);

    expect(await screen.findByText('发票 · CNY 128.50')).toBeTruthy();
    fireEvent.click(screen.getByText('查看详情'));

    expect(await screen.findByText('OCR 未配置或不可达，已转人工')).toBeTruthy();
    expect(screen.getByText('原因码：OCR_NOT_CONFIGURED')).toBeTruthy();
    expect(screen.getByText('发票验真服务未接入，按策略必须人工复核')).toBeTruthy();
    expect(screen.getByText('AI 预审完成')).toBeTruthy();
  });

  it('uploads then submits the file id and doc type', async () => {
    const apiClient = client();
    render(<ExpenseView apiClient={apiClient} permissions={ALL_PERMISSIONS} />);
    await screen.findByText('发票 · CNY 128.50');

    const file = new File(['abc'], 'invoice.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('选择凭证文件'), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: '上传并提交预审' }));

    // antd Button 的 loading 动效会让 RTL 的 waitFor 无法结算，这里改用宏任务轮询刷新微任务队列。
    await flushUntil(() => screen.queryByText(/转人工复核/) !== null);

    expect(apiClient.createUploadSession).toHaveBeenCalledWith({
      fileName: 'invoice.png',
      contentType: 'image/png',
      sizeBytes: 3,
    });
    expect(apiClient.completeUpload).toHaveBeenCalledWith('f1');
    expect(apiClient.createExpenseDocument).toHaveBeenCalledWith({
      fileId: 'f1',
      docType: 'invoice',
    });
    expect(screen.getByText(/转人工复核/)).toBeTruthy();
  }, 15000);

  it('warns that PDF will be manually reviewed', async () => {
    render(<ExpenseView apiClient={client()} permissions={ALL_PERMISSIONS} />);
    await screen.findByText('发票 · CNY 128.50');

    const file = new File(['pdf'], 'invoice.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText('选择凭证文件'), { target: { files: [file] } });

    expect(await screen.findByText('PDF 不做自动 OCR')).toBeTruthy();
  });

  it('hides review actions without review permission', async () => {
    render(<ExpenseView
      apiClient={client()}
      permissions={new Set(['expense.document.read', 'expense.document.submit'])}
    />);
    await screen.findByText('发票 · CNY 128.50', {}, { timeout: 1000 });
    fireEvent.click(screen.getByText('查看详情'));
    await screen.findByText('逐条规则', {}, { timeout: 1000 });
    expect(screen.queryByText('复核通过')).toBeNull();
    expect(screen.queryByText('人工复核')).toBeNull();
  });

  it('requires a reject reason before submitting review', async () => {
    const apiClient = client();
    render(<ExpenseView apiClient={apiClient} permissions={ALL_PERMISSIONS} />);
    await screen.findByText('发票 · CNY 128.50', {}, { timeout: 1000 });
    fireEvent.click(screen.getByText('查看详情'));
    await screen.findByText('人工复核', {}, { timeout: 1000 });

    fireEvent.click(screen.getByRole('button', { name: /驳\s*回/ }));
    expect(screen.getByText('驳回必须填写原因。')).toBeTruthy();
    expect(apiClient.reviewExpenseDocument).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('复核意见'), { target: { value: '抬头不符' } });
    fireEvent.click(screen.getByRole('button', { name: /驳\s*回/ }));
    expect(apiClient.reviewExpenseDocument).toHaveBeenCalledWith(
      'd1', { decision: 'reject', comment: '抬头不符' },
    );
  }, 15000);

  it('requires a reason before revoking a posted document', async () => {
    const apiClient = client({
      listExpenseDocuments: vi.fn(async () => [viewFixture({ status: 'posted' })]),
    });
    render(<ExpenseView apiClient={apiClient} permissions={ALL_PERMISSIONS} />);
    await screen.findByText(/已入账/, {}, { timeout: 1000 });
    fireEvent.click(screen.getByText('查看详情'));
    await screen.findByText('撤销入账', {}, { timeout: 1000 });

    fireEvent.click(screen.getByRole('button', { name: /^撤\s*销$/ }));
    expect(screen.getByText('撤销必须填写原因。')).toBeTruthy();
    expect(apiClient.voidExpenseDocument).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('撤销原因'), { target: { value: '发票作废' } });
    fireEvent.click(screen.getByRole('button', { name: /^撤\s*销$/ }));
    expect(apiClient.voidExpenseDocument).toHaveBeenCalledWith(
      'd1', { reason: '发票作废' },
    );
  }, 15000);

  it('only shows policy tab with manage permission and saves changes', async () => {
    render(<ExpenseView
      apiClient={client()}
      permissions={new Set(['expense.document.read'])}
    />);
    await screen.findByText('发票 · CNY 128.50');
    expect(screen.queryByText('租户策略')).toBeNull();
    expect(screen.queryByText('异常告警')).toBeNull();
  });

  it('lists active batch holds and releases with a note', async () => {
    const apiClient = client({ listExpenseHolds: vi.fn(async () => [holdFixture]) });
    render(<ExpenseView apiClient={apiClient} permissions={ALL_PERMISSIONS} />);
    await screen.findByText('发票 · CNY 128.50');

    fireEvent.click(screen.getByText('异常告警'));
    expect(await screen.findByText(holdFixture.reason)).toBeTruthy();
    expect(screen.getByText('1 个批次已暂停自动入账')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /^解\s*除$/ }));
    expect(screen.getByText('解除暂停必须填写说明。')).toBeTruthy();
    expect(apiClient.releaseExpenseHold).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(`解除说明-${holdFixture.id}`), {
      target: { value: '已核对为正常批量报销' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^解\s*除$/ }));

    await flushUntil(() => screen.queryByText('解除暂停必须填写说明。') === null);
    expect(apiClient.releaseExpenseHold).toHaveBeenCalledWith(
      holdFixture.id, { note: '已核对为正常批量报销' },
    );
  }, 15000);
});
