import { createApiClient } from '@platform/api-client';
import { toCssVariables } from '@platform/design-system';
import { ConfigProvider } from 'antd';
import 'antd/dist/reset.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { platformTheme } from './theme';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('root element is missing');
}

for (const [name, value] of Object.entries(toCssVariables())) {
  document.documentElement.style.setProperty(name, value);
}

const apiClient = createApiClient({ baseUrl: '' });

createRoot(rootElement).render(
  <StrictMode>
    <ConfigProvider theme={platformTheme}>
      <App apiClient={apiClient} />
    </ConfigProvider>
  </StrictMode>,
);
