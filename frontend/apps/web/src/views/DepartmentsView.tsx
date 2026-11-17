import type { ApiClient, DepartmentSummary } from '@platform/api-client';
import { Card, Table, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { AsyncState, type AsyncPhase } from '../components/AsyncState';
import { describeError } from '../utils/format';

const { Link } = Typography;

export interface DepartmentsViewProps {
  apiClient: ApiClient;
}

/** 部门列表：只显示当前主体有权进入的空间。 */
export function DepartmentsView({ apiClient }: DepartmentsViewProps) {
  const [items, setItems] = useState<DepartmentSummary[]>([]);
  const [phase, setPhase] = useState<AsyncPhase>('loading');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setPhase('loading');
    apiClient
      .listDepartments()
      .then((list) => {
        setItems(list);
        setPhase(list.length === 0 ? 'empty' : 'ready');
      })
      .catch((cause: unknown) => {
        setError(describeError(cause));
        setPhase('error');
      });
  }, [apiClient]);

  useEffect(load, [load]);

  return (
    <Card title="部门工作区">
      <AsyncState
        phase={phase}
        {...(error ? { errorMessage: error } : {})}
        emptyText="当前没有可访问的部门"
        onRetry={load}
      >
        <Table<DepartmentSummary>
          rowKey="orgUnitId"
          pagination={false}
          dataSource={items}
          columns={[
            {
              title: '部门',
              dataIndex: 'name',
              render: (name: string, row) => <a href={row.path}>{name}</a>,
            },
            {
              title: '空间地址',
              dataIndex: 'path',
              render: (path: string) => <Link href={path}>{path}</Link>,
            },
          ]}
        />
      </AsyncState>
    </Card>
  );
}
