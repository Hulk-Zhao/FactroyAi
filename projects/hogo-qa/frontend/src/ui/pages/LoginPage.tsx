/**
 * LoginPage —— 演示登录门禁页（部署在云端做作品展示时的第一屏）。
 *
 * 风格参考 mfg-agent 演示门户：工业深色底 + 品牌标记 + 玻璃拟态卡片。
 * 门禁目的是防恶意刷访问（单口令 + 后端按 IP 限流），不是用户体系，
 * 因此页面上明示演示口令，访客按提示即可进入。
 */

import { Alert, Box, Button, Chip, CircularProgress, InputAdornment, TextField, Typography } from '@mui/material';
import { LockOutlined as LockIcon, Visibility, VisibilityOff } from '@mui/icons-material';
import { useState, type FormEvent, type ReactElement } from 'react';
import { login } from '@/services/auth/authClient';

/** 页面主题色（工业深蓝，与 mfg-agent 门户同系）。 */
const PAGE_BG = 'linear-gradient(160deg, #080d18 0%, #0c1426 55%, #101b33 100%)';
const CARD_BG = 'rgba(255,255,255,0.045)';
const ACCENT = '#5b8cff';

/** 特性卖点（登录页上的"作品名片"）。 */
const FEATURES = ['SPC 控制图', 'Cp · Cpk · Pp · Ppk', '12 条判异准则', '柏拉图', 'AI 解读（可选）', 'Excel / Word 报表'];

/**
 * 渲染登录页。
 *
 * @param props.onSuccess 登录成功回调（AuthGate 据此放行）
 * @returns 登录页元素
 */
export default function LoginPage({ onSuccess }: { onSuccess: () => void }): ReactElement {
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** 提交登录；失败信息直接展示（含限流锁定提示）。 */
  const handleSubmit = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    const outcome = await login(password);
    setSubmitting(false);
    if (outcome.ok) {
      onSuccess();
    } else {
      setError(outcome.message);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        // 深色渐变底。注意：不能再在本元素上用 backgroundImage 叠网格——
        // background 简写的 image 层会被后续 backgroundImage 覆盖，深色底会整个丢失。
        background: PAGE_BG,
        px: 2,
        py: 6,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* 细网格纹理：独立覆盖层（工业图纸质感，纯 CSS，无外部资源）。 */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          zIndex: 0,
          pointerEvents: 'none',
          backgroundImage:
            'linear-gradient(rgba(91,140,255,0.07) 1px, transparent 1px), linear-gradient(90deg, rgba(91,140,255,0.07) 1px, transparent 1px)',
          backgroundSize: '44px 44px',
        }}
      />
      <Box
        sx={{
          width: '100%',
          maxWidth: 420,
          position: 'relative',
          zIndex: 1,
          bgcolor: CARD_BG,
          border: '1px solid rgba(91,140,255,0.22)',
          borderRadius: 3,
          backdropFilter: 'blur(10px)',
          px: { xs: 3, sm: 4.5 },
          py: { xs: 4, sm: 5 },
          boxShadow: '0 24px 80px rgba(0,0,0,0.45)',
        }}
        data-testid="login-card"
      >
        {/* 品牌区 */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
          <Box
            aria-hidden
            sx={{
              width: 44,
              height: 44,
              borderRadius: 2,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              bgcolor: 'rgba(91,140,255,0.14)',
              border: '1px solid rgba(91,140,255,0.35)',
            }}
          >
            {/* 品牌标记：H（与仓库 favicon 同形） */}
            <svg viewBox="0 0 32 32" width="24" height="24" aria-hidden>
              <path d="M9 9v14M23 9v14M9 16h14" stroke={ACCENT} strokeWidth="3" strokeLinecap="round" fill="none" />
            </svg>
          </Box>
          <Box>
            <Typography variant="h6" fontWeight={700} sx={{ color: '#eef2ff', lineHeight: 1.2 }}>
              Hogo-QA-tool
            </Typography>
            <Typography variant="caption" sx={{ color: 'rgba(238,242,255,0.55)', letterSpacing: 1 }}>
              OFFLINE SPC ANALYSIS TOOL
            </Typography>
          </Box>
        </Box>

        <Typography variant="body2" sx={{ color: 'rgba(238,242,255,0.85)', mb: 2.5 }}>
          离线质量过程能力分析工具 · 导入测量数据，得到能力指数、控制图判异、柏拉图与一键日报。
        </Typography>

        {/* 特性徽章 */}
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 3 }}>
          {FEATURES.map((f) => (
            <Chip
              key={f}
              label={f}
              size="small"
              sx={{
                bgcolor: 'rgba(91,140,255,0.10)',
                color: 'rgba(238,242,255,0.82)',
                border: '1px solid rgba(91,140,255,0.25)',
                fontSize: 12,
              }}
            />
          ))}
        </Box>

        {/* 登录表单 */}
        <form onSubmit={handleSubmit} noValidate>
          <TextField
            fullWidth
            size="small"
            type={showPwd ? 'text' : 'password'}
            label="访问口令"
            placeholder="请输入演示口令"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={!!error}
            autoFocus
            data-testid="login-password"
            InputLabelProps={{ style: { color: 'rgba(238,242,255,0.6)' } }}
            sx={{
              mb: 1.5,
              '& .MuiOutlinedInput-root': {
                color: '#eef2ff',
                '& fieldset': { borderColor: 'rgba(91,140,255,0.35)' },
                '&:hover fieldset': { borderColor: 'rgba(91,140,255,0.6)' },
                '&.Mui-focused fieldset': { borderColor: ACCENT },
              },
            }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <LockIcon sx={{ color: 'rgba(238,242,255,0.45)', fontSize: 18 }} />
                </InputAdornment>
              ),
              endAdornment: (
                <InputAdornment position="end" sx={{ cursor: 'pointer' }} onClick={() => setShowPwd((v) => !v)}>
                  {showPwd ? (
                    <VisibilityOff sx={{ color: 'rgba(238,242,255,0.45)', fontSize: 18 }} />
                  ) : (
                    <Visibility sx={{ color: 'rgba(238,242,255,0.45)', fontSize: 18 }} />
                  )}
                </InputAdornment>
              ),
            }}
          />

          {error && (
            <Alert severity="error" sx={{ mb: 1.5, '& .MuiAlert-message': { fontSize: 13 } }} data-testid="login-error">
              {error}
            </Alert>
          )}

          <Button
            type="submit"
            fullWidth
            variant="contained"
            disabled={submitting || password.length === 0}
            data-testid="login-submit"
            sx={{
              py: 1.1,
              fontWeight: 700,
              bgcolor: ACCENT,
              '&:hover': { bgcolor: '#7aa2ff' },
              '&.Mui-disabled': { bgcolor: 'rgba(91,140,255,0.25)', color: 'rgba(238,242,255,0.6)' },
            }}
            startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : null}
          >
            {submitting ? '验证中…' : '进入 Demo'}
          </Button>
        </form>

        {/* 口令提示：门禁用于防刷，不是保密；上线可在 backend/.env 修改 AUTH_PASSWORD */}
        <Typography variant="caption" sx={{ display: 'block', mt: 2, color: 'rgba(238,242,255,0.6)' }} data-testid="login-hint">
          演示口令：123 · 仅用于防止恶意访问 · 连续输错会被临时锁定
        </Typography>
      </Box>

      <Typography
        variant="caption"
        sx={{ position: 'absolute', bottom: 16, left: 0, right: 0, zIndex: 1, textAlign: 'center', color: 'rgba(238,242,255,0.5)' }}
      >
        Hogo-QA-tool · 离线优先 · 统计内核零 DOM 依赖，结果可复算
      </Typography>
    </Box>
  );
}
