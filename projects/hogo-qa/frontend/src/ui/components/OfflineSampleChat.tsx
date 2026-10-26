/**
 * OfflineSampleChat —— 离线模式下的「样例 AI 对话」演示组件。
 *
 * 背景：离线（file://）或未配置 AI 服务时，AI 助手页原本把样例问答平铺成
 * 静态图文，形态与在线模式的对话流差异明显。
 *
 * 本组件把样例问答改成真实对话流：
 * 1. 进入时只展示候选问题按钮，不预先铺开答案；
 * 2. 点击问题 → 用户气泡进入对话区，随后追加对应 AI 回答气泡；
 * 3. 已问过的问题按钮置灰，可继续问其他问题；
 * 4. 对话区为定高滚动视窗，遵循 chatScroll 的微信式贴底契约；
 * 5. 提供「重新开始」清空对话。
 *
 * 数据来源：SampleDatasetSpec.aiExamples（内置合成样例，无网络请求）。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import { RestartAlt as RestartAltIcon, SmartToy as SmartToyIcon } from '@mui/icons-material';
import type { ReactElement } from 'react';
import { isNearBottom, scrollToBottom } from '@/ui/chatScroll';
import type { SampleDatasetSpec } from '@/data/samples/sampleDatasets';

/** 对话区一条消息。 */
interface OfflineMessage {
  id: string;
  role: 'user' | 'ai';
  content: string;
  /** 对应的样例问题下标（用于标记已问过）。 */
  exampleIndex: number;
}

/** 会话容器高度（px）：与在线模式的微信式视窗观感保持一致。 */
const CHAT_VIEWPORT_HEIGHT = 420;

/**
 * 渲染离线样例对话演示。
 *
 * @param props 含样例规约
 * @returns 页面元素
 */
export default function OfflineSampleChat({ spec }: { spec: SampleDatasetSpec }): ReactElement {
  const [messages, setMessages] = useState<OfflineMessage[]>([]);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);

  const asked = new Set(messages.filter((m) => m.role === 'user').map((m) => m.exampleIndex));

  /** 用户上翻时退出贴底态。 */
  const handleScroll = useCallback((): void => {
    stickRef.current = isNearBottom(scrollRef.current);
  }, []);

  useEffect(() => {
    if (stickRef.current) {
      scrollToBottom(scrollRef.current);
    }
  }, [messages]);

  /** 点击候选问题：追加一问一答。 */
  const ask = useCallback(
    (index: number): void => {
      const ex = spec.aiExamples[index];
      if (!ex) {
        return;
      }
      stickRef.current = true;
      setMessages((prev) => [
        ...prev,
        { id: `u-${index}-${prev.length}`, role: 'user', content: ex.question, exampleIndex: index },
        { id: `a-${index}-${prev.length}`, role: 'ai', content: ex.answer, exampleIndex: index },
      ]);
    },
    [spec],
  );

  /** 清空对话。 */
  const reset = useCallback((): void => {
    stickRef.current = true;
    setMessages([]);
  }, []);

  return (
    <Stack spacing={2} data-testid="ai-assistant-offline">
      <Stack direction="row" spacing={1} alignItems="center">
        <SmartToyIcon color="primary" />
        <Typography variant="h6" fontWeight={600}>
          AI 质量分析助手（样例对话演示）
        </Typography>
        <Box sx={{ flex: 1 }} />
        {messages.length > 0 ? (
          <Button
            size="small"
            startIcon={<RestartAltIcon />}
            onClick={reset}
            data-testid="sample-chat-reset"
          >
            重新开始
          </Button>
        ) : null}
      </Stack>

      <Alert severity="info">
        当前为离线模式，AI 服务未启用。以下为样例「{spec.title}」的对话演示：点击下方问题，即可查看对应的分析回答。
      </Alert>

      <Box
        ref={scrollRef}
        onScroll={handleScroll}
        data-testid="sample-chat-viewport"
        sx={{
          height: CHAT_VIEWPORT_HEIGHT,
          overflowY: 'auto',
          p: 1.5,
          borderRadius: 1,
          bgcolor: 'action.hover',
        }}
      >
        {messages.length === 0 ? (
          <Stack alignItems="center" justifyContent="center" spacing={1} sx={{ height: '100%' }}>
            <SmartToyIcon color="disabled" sx={{ fontSize: 40 }} />
            <Typography variant="body2" color="text.secondary">
              选择下方任一问题开始对话
            </Typography>
          </Stack>
        ) : (
          <Stack spacing={1.5}>
            {messages.map((m) => (
              <Stack
                key={m.id}
                direction="row"
                spacing={1}
                alignItems="flex-start"
                justifyContent={m.role === 'user' ? 'flex-end' : 'flex-start'}
              >
                {m.role === 'ai' ? (
                  <Chip size="small" label="AI" variant="outlined" sx={{ mt: 0.5 }} />
                ) : null}
                <Box
                  sx={{
                    maxWidth: '80%',
                    px: 1.5,
                    py: 1,
                    borderRadius: 1.5,
                    boxShadow: 1,
                    bgcolor: m.role === 'user' ? 'primary.main' : 'background.paper',
                    color: m.role === 'user' ? 'primary.contrastText' : 'text.primary',
                  }}
                >
                  <Typography
                    variant="body2"
                    component="pre"
                    sx={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', m: 0 }}
                  >
                    {m.content}
                  </Typography>
                </Box>
                {m.role === 'user' ? (
                  <Chip size="small" label="我" color="primary" sx={{ mt: 0.5 }} />
                ) : null}
              </Stack>
            ))}
          </Stack>
        )}
      </Box>

      <Card variant="outlined">
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Typography variant="caption" color="text.secondary">
            可以这样问：
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
            {spec.aiExamples.map((ex, idx) => (
              <Button
                key={`q-${idx}`}
                size="small"
                variant={asked.has(idx) ? 'text' : 'outlined'}
                disabled={asked.has(idx)}
                onClick={() => ask(idx)}
                data-testid={`sample-chat-ask-${idx}`}
              >
                {ex.question}
              </Button>
            ))}
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
