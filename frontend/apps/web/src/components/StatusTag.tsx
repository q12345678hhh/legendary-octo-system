import type { FileScanStatus, FileStatus, JobStatus } from '@platform/api-client';
import { Tag } from 'antd';

const JOB_COLORS: Record<JobStatus, string> = {
  queued: 'default',
  running: 'processing',
  succeeded: 'green',
  failed: 'red',
  cancelled: 'orange',
};

const JOB_LABELS: Record<JobStatus, string> = {
  queued: '排队中',
  running: '执行中',
  succeeded: '成功',
  failed: '失败',
  cancelled: '已取消',
};

const FILE_COLORS: Record<FileStatus, string> = {
  pending_upload: 'default',
  uploaded: 'processing',
  available: 'green',
  rejected: 'red',
};

const FILE_LABELS: Record<FileStatus, string> = {
  pending_upload: '待上传',
  uploaded: '已上传',
  available: '可用',
  rejected: '已拒绝',
};

const SCAN_COLORS: Record<FileScanStatus, string> = {
  pending: 'default',
  scanning: 'processing',
  clean: 'green',
  infected: 'red',
  failed: 'orange',
};

const SCAN_LABELS: Record<FileScanStatus, string> = {
  pending: '待扫描',
  scanning: '扫描中',
  clean: '扫描通过',
  infected: '发现威胁',
  failed: '扫描失败',
};

export function JobStatusTag({ status }: { status: JobStatus }) {
  return <Tag color={JOB_COLORS[status]}>{JOB_LABELS[status]}</Tag>;
}

export function FileStatusTag({ status }: { status: FileStatus }) {
  return <Tag color={FILE_COLORS[status]}>{FILE_LABELS[status]}</Tag>;
}

export function ScanStatusTag({ status }: { status: FileScanStatus }) {
  return <Tag color={SCAN_COLORS[status]}>{SCAN_LABELS[status]}</Tag>;
}
