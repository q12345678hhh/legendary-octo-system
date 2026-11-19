import type { ApiClient } from '@platform/api-client';
import { tokens } from '@platform/design-system';
import { Alert, Button, Card, Input, Space, Typography } from 'antd';
import { useState, type FormEvent } from 'react';
import { isUuid, saveSession, type Session } from '../session';
import { describeError } from '../utils/format';

const { Title, Text, Paragraph } = Typography;

/** 本地演示租户；登录请求必须带 X-Tenant-Id，后端按它做租户隔离。 */
export const DEMO_TENANT_ID = 'aaaaaaaa-0000-0000-0000-000000000001';

export interface LoginViewProps {
  apiClient: ApiClient;
  onSignedIn: (session: Session) => void;
}

export function LoginView({ apiClient, onSignedIn }: LoginViewProps) {
  const [tenantId, setTenantId] = useState(DEMO_TENANT_ID);
  const [email, setEmail] = useState('demo.manager@example.test');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const tenant = tenantId.trim();

    if (!isUuid(tenant)) {
      setError('租户 ID 必须是标准 UUID，例如 11111111-1111-4111-8111-111111111111。');
      return;
    }
    if (!email.trim()) {
      setError('请输入登录邮箱。');
      return;
    }
    if (!password) {
      setError('请输入密码。');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const result = await apiClient.signIn({ tenantId: tenant, email: email.trim(), password });
      if (result.status !== 'session') {
        setError('该账号需要 MFA 验证；演示环境请使用无 MFA 的账号。');
        return;
      }
      const session: Session = {
        tenantId: result.principal.tenantId,
        subjectId: result.principal.actorId,
      };
      saveSession(session);
      onSignedIn(session);
    } catch (cause) {
      setError(describeError(cause, '登录失败，请检查邮箱与密码。'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card style={{ maxWidth: 560, margin: '0 auto' }}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Title level={3} style={{ marginBottom: tokens.spacing.xs }}>
            登录平台
          </Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            凭据由后端校验，成功后下发 HttpOnly 会话 Cookie；写操作自动携带 CSRF 令牌。
          </Paragraph>
        </div>

        {error && <Alert type="error" showIcon message={error} />}

        <form onSubmit={handleSubmit} aria-label="登录表单">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <label htmlFor="login-tenant" style={{ display: 'block' }}>
              <Text strong>租户 ID</Text>
              <Input
                id="login-tenant"
                value={tenantId}
                onChange={(event) => setTenantId(event.target.value)}
                placeholder="aaaaaaaa-0000-0000-0000-000000000001"
                autoComplete="off"
                aria-describedby="login-tenant-hint"
                aria-invalid={error !== null && !isUuid(tenantId)}
                style={{ marginTop: tokens.spacing.xs }}
              />
              <Text id="login-tenant-hint" type="secondary" style={{ fontSize: 12 }}>
                登录请求通过 X-Tenant-Id 声明租户，后端据此校验账号归属。
              </Text>
            </label>

            <label htmlFor="login-email" style={{ display: 'block' }}>
              <Text strong>邮箱</Text>
              <Input
                id="login-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="demo.manager@example.test"
                autoComplete="username"
                style={{ marginTop: tokens.spacing.xs }}
              />
            </label>

            <label htmlFor="login-password" style={{ display: 'block' }}>
              <Text strong>密码</Text>
              <Input.Password
                id="login-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                style={{ marginTop: tokens.spacing.xs }}
              />
            </label>

            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Space>
        </form>
      </Space>
    </Card>
  );
}
