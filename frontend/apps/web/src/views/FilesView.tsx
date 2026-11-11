import type { ApiClient, FileMetadata } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import { Alert, Button, Card, Space, Table, Typography } from 'antd';
import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import { AsyncState } from '../components/AsyncState';
import { FileStatusTag, ScanStatusTag } from '../components/StatusTag';
import { describeError, formatDateTime, shortId } from '../utils/format';

const { Text, Paragraph } = Typography;

export interface FilesViewProps {
  apiClient: ApiClient;
}

function formatBytes(value: number): string {
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function FilesView({ apiClient }: FilesViewProps) {
  const [files, setFiles] = useState<FileMetadata[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);

  const refresh = useCallback(() => {
    return apiClient
      .listFiles()
      .then((list) => {
        setFiles(list);
        setLoadError(null);
      })
      .catch((error: unknown) => setLoadError(describeError(error)));
  }, [apiClient]);

  useEffect(() => {
    void refresh();
    const timer = globalThis.setInterval(() => {
      void refresh();
    }, 2000);
    return () => globalThis.clearInterval(timer);
  }, [refresh]);

  function handleSelect(event: ChangeEvent<HTMLInputElement>): void {
    const file = event.target.files?.[0] ?? null;
    setSelectedFile(file);
    setActionError(null);
    setNotice(null);
  }

  async function handleUpload(): Promise<void> {
    if (!selectedFile) {
      setActionError('请先选择要上传的文件。');
      return;
    }

    setUploading(true);
    setActionError(null);
    setNotice(null);

    try {
      const contentType = selectedFile.type || 'application/octet-stream';
      const session = await apiClient.createUploadSession({
        fileName: selectedFile.name,
        contentType,
        sizeBytes: selectedFile.size,
      });

      // 预签名 URL 是 bearer 凭证，只在当前动作内使用，不写入日志。
      const body = await selectedFile.arrayBuffer();
      const putResponse = await fetch(session.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': contentType },
        body,
      });
      if (!putResponse.ok) {
        throw new Error(`对象存储上传失败：HTTP ${putResponse.status}`);
      }

      const metadata = await apiClient.completeUpload(session.fileId);
      setNotice(`文件 ${metadata.fileName} 已上传，等待扫描结果。`);
      setSelectedFile(null);
      await refresh();
    } catch (error) {
      setActionError(describeError(error));
    } finally {
      setUploading(false);
    }
  }

  async function handleDownload(file: FileMetadata): Promise<void> {
    setActionError(null);
    setNotice(null);
    try {
      const grant = await apiClient.getDownloadUrl(file.fileId);
      globalThis.open(grant.url, '_blank', 'noopener');
      setNotice(`已生成短时下载链接，有效期至 ${formatDateTime(grant.expiresAt)}。`);
    } catch (error) {
      setActionError(describeError(error));
    }
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="上传文件">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            上传分两步：先申请预签名 PUT 地址直传对象存储，再调用完成接口触发扫描。
            只有扫描通过的同一主体才能获取下载地址；跨主体或跨租户会被拒绝。
          </Paragraph>

          {actionError && <Alert type="error" showIcon message={actionError} />}
          {notice && <Alert type="success" showIcon message={notice} />}

          <Space align="center" wrap>
            <label htmlFor="file-input">
              <Text strong>选择文件</Text>
              <input
                id="file-input"
                type="file"
                onChange={handleSelect}
                aria-describedby="file-input-hint"
                style={{ display: 'block', marginTop: tokens.spacing.xs }}
              />
            </label>
            <Button type="primary" loading={uploading} onClick={() => void handleUpload()}>
              上传并等待扫描
            </Button>
            {selectedFile && (
              <Text type="secondary">
                {selectedFile.name}（{formatBytes(selectedFile.size)}）
              </Text>
            )}
          </Space>
          <Text id="file-input-hint" type="secondary" style={{ fontSize: 12 }}>
            演示用文件名包含 <Text code>eicar</Text> 会被标记为威胁；可执行扩展名会被拒绝。
          </Text>
        </Space>
      </Card>

      <Card title="文件列表">
        {loadError ? (
          <AsyncState
            phase="error"
            errorTitle="文件列表加载失败"
            errorMessage={loadError}
            onRetry={() => void refresh()}
          />
        ) : files === null ? (
          <AsyncState phase="loading" loadingText="正在读取文件…" />
        ) : files.length === 0 ? (
          <AsyncState phase="empty" emptyText="还没有文件，先上传一个。" />
        ) : (
          <Table<FileMetadata>
            size="small"
            rowKey="fileId"
            pagination={false}
            dataSource={files}
            columns={[
              {
                title: '文件',
                dataIndex: 'fileName',
                render: (_value, record) => (
                  <Space direction="vertical" size={0}>
                    <Text>{record.fileName}</Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {shortId(record.fileId, 12)} · {formatBytes(record.sizeBytes)}
                    </Text>
                  </Space>
                ),
              },
              {
                title: '类型',
                dataIndex: 'contentType',
                render: (_value, record) => (
                  <Text code style={{ fontSize: 12 }}>
                    {record.contentType}
                  </Text>
                ),
              },
              {
                title: '上传状态',
                dataIndex: 'status',
                render: (_value, record) => <FileStatusTag status={record.status} />,
              },
              {
                title: '扫描状态',
                dataIndex: 'scanStatus',
                render: (_value, record) => (
                  <Space direction="vertical" size={0}>
                    <ScanStatusTag status={record.scanStatus} />
                    {record.scanDetail && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {record.scanDetail}
                      </Text>
                    )}
                  </Space>
                ),
              },
              {
                title: '上传时间',
                dataIndex: 'uploadedAt',
                render: (_value, record) => formatDateTime(record.uploadedAt),
              },
              {
                title: '操作',
                key: 'actions',
                render: (_value, record) => (
                  <Button
                    size="small"
                    disabled={record.scanStatus !== 'clean'}
                    onClick={() => void handleDownload(record)}
                  >
                    获取下载地址
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Card>
    </Space>
  );
}
