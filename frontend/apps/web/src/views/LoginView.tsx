import { tokens } from '@platform/design-system';
import { Alert, Button, Card, Input, Space, Typography } from 'antd';
import { useState, type FormEvent } from 'react';
import { isUuid, saveSession, type Session } from '../session';

const { Title, Text, Paragraph } = Typography;

export interface LoginViewProps {
  onSignedIn: (session: Session) => void;
}

export function LoginView({ onSignedIn }: LoginViewProps) {
  const [tenantId, setTenantId] = useState('');
  const [subjectId, setSubjectId] = useState('');
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const tenant = tenantId.trim();
    const subject = subjectId.trim();

    if (!isUuid(tenant)) {
      setError('租户 ID 必须是标准 UUID，例如 11111111-1111-4111-8111-111111111111。');
      return;
    }
    if (!isUuid(subject)) {
      setError('主体 ID 必须是标准 UUID。');
      return;
    }

    const session: Session = { tenantId: tenant, subjectId: subject };
    saveSession(session);
    setError(null);
    onSignedIn(session);
  }

  return (
    <Card style={{ maxWidth: 560, margin: '0 auto' }}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Title level={3} style={{ marginBottom: tokens.spacing.xs }}>
            使用占位身份进入
          </Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            当前为骨架阶段的占位认证：租户与主体仅通过请求头声明，不校验凭据。
            共享环境禁止启用，正式认证由后续工作包替换。
          </Paragraph>
        </div>

        {error && <Alert type="error" showIcon message={error} />}

        <form onSubmit={handleSubmit} aria-label="占位登录表单">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <label htmlFor="login-tenant" style={{ display: 'block' }}>
              <Text strong>租户 ID</Text>
              <Input
                id="login-tenant"
                value={tenantId}
                onChange={(event) => setTenantId(event.target.value)}
                placeholder="11111111-1111-4111-8111-111111111111"
                autoComplete="off"
                aria-describedby="login-tenant-hint"
                aria-invalid={error !== null && !isUuid(tenantId)}
                style={{ marginTop: tokens.spacing.xs }}
              />
              <Text id="login-tenant-hint" type="secondary" style={{ fontSize: 12 }}>
                对应数据库中的租户主键，决定 RLS 可见范围。
              </Text>
            </label>

            <label htmlFor="login-subject" style={{ display: 'block' }}>
              <Text strong>主体 ID</Text>
              <Input
                id="login-subject"
                value={subjectId}
                onChange={(event) => setSubjectId(event.target.value)}
                placeholder="22222222-2222-4222-8222-222222222222"
                autoComplete="off"
                aria-describedby="login-subject-hint"
                aria-invalid={error !== null && !isUuid(subjectId)}
                style={{ marginTop: tokens.spacing.xs }}
              />
              <Text id="login-subject-hint" type="secondary" style={{ fontSize: 12 }}>
                用于幂等、作业与文件归属校验；跨主体下载会被拒绝。
              </Text>
            </label>

            <Button type="primary" htmlType="submit" block>
              进入平台
            </Button>
          </Space>
        </form>
      </Space>
    </Card>
  );
}
