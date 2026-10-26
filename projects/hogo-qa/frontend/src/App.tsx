import { CssBaseline, ThemeProvider } from '@mui/material';
import type { ReactElement } from 'react';
import { theme } from './theme';
import AppShell from './ui/layout/AppShell';
import AuthGate from './ui/auth/AuthGate';

/**
 * App —— 应用根组件。
 *
 * T03 落地左导航 + 顶栏主布局，承载数据导入 / 分析 / 报表等页面路由。
 * AuthGate 为云端作品展示形态的登录门禁（file:// 离线包自动跳过，见 authClient）。
 */
export default function App(): ReactElement {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <AuthGate>
        <AppShell />
      </AuthGate>
    </ThemeProvider>
  );
}
