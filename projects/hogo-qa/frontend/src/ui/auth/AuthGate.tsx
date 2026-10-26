/**
 * AuthGate —— 登录门禁包装器。
 *
 * 三态：checking（服务端确认中）/ authed（放行渲染主应用）/ anonymous（登录页）。
 * 关闭条件（isAuthRequired）：`file://` 离线包、`VITE_AUTH_DISABLED=1`、单测环境。
 *
 * fail-closed：服务端确认失败 → 回到登录页；网络瞬断时 checkSession 会
 * 保守放行本地未过期会话（token 自带过期兜底），避免后端瞬断误踢用户。
 */

import { Box, CircularProgress } from '@mui/material';
import { useEffect, useState, type ReactElement } from 'react';
import { checkSession, getValidSession, isAuthRequired } from '@/services/auth/authClient';
import LoginPage from '@/ui/pages/LoginPage';

type GateState = 'checking' | 'authed' | 'anonymous';

/** 门禁校验中的全屏占位（避免主应用闪现）。 */
function Splash(): ReactElement {
  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: '#0c1426',
      }}
    >
      <CircularProgress size={28} sx={{ color: '#5b8cff' }} />
    </Box>
  );
}

/**
 * 门禁包装器。
 *
 * @param props.children 门禁放行后渲染的主应用
 * @returns 门禁元素
 */
export default function AuthGate({ children }: { children: ReactElement }): ReactElement {
  const [state, setState] = useState<GateState>(() => (isAuthRequired() ? 'checking' : 'authed'));

  useEffect(() => {
    if (!isAuthRequired()) return;
    let cancelled = false;
    const session = getValidSession();
    if (!session) {
      setState('anonymous');
      return;
    }
    checkSession().then((ok) => {
      if (!cancelled) setState(ok ? 'authed' : 'anonymous');
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state === 'checking') return <Splash />;
  if (state === 'anonymous') return <LoginPage onSuccess={() => setState('authed')} />;
  return children;
}
