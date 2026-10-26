/**
 * SampleDataPage —— 样例数据集展示页（作品展示的"一键体验"入口）。
 *
 * 设计口径：
 * - 内置 3 个合成样例（见 `@/data/samples/sampleDatasets`），卡片上的每个
 *   Ppk 都由 `@/core` 实时计算——页面展示与真实分析走同一条统计内核，
 *   所有数字可复算。
 * - 「载入并分析」走 projectStore.setDataset（导入 = 新项目语义），
 *   载入后跳能力分析页；「下载 XLSX」在浏览器端生成与仓库标准样例
 *   同构的 Excel，可直接走「数据导入」页回灌。
 */

import { Box, Button, Chip, Grid, Paper, Stack, Typography } from '@mui/material';
import { PlayArrow as PlayArrowIcon, Download as DownloadIcon, AutoAwesome as SparkIcon } from '@mui/icons-material';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useMemo, useState, type ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { capabilityGrade, computeCapability, buildSubgroups } from '@/core';
import type { SpecLimits } from '@/core';
import { useProjectStore } from '@/store/projectStore';
import { useUiStore } from '@/store/uiStore';
import {
  SAMPLE_DATASETS,
  buildSampleDataset,
  downloadSampleXlsx,
  type SampleDatasetSpec,
} from '@/data/samples/sampleDatasets';
import { generateSampleValues } from '@/data/samples/sampleGenerator';

/** 能力等级 → 展示色（与 verdict 语义对齐）。 */
const GRADE_COLOR: Record<string, string> = {
  excellent: '#2e7d32',
  qualified: '#1565c0',
  low: '#ed6c02',
  fail: '#d32f2f',
  unknown: '#9e9e9e',
};

/** 单特性的预计算结果（卡片渲染输入）。 */
interface CharacteristicPreview {
  name: string;
  unit: string;
  values: number[];
  lsl: number;
  usl: number;
  cpk: number | null;
  ppk: number | null;
  grade: string;
}

/**
 * 展示页预览的子组容量：与能力分析页默认口径一致
 * （`analysisStore.DEFAULT_CAPACITY` = 5、`samples/verify.ts` 同值），
 * 保证「卡片上的数字 = 载入后能力分析页看到的数字」。
 */
const PREVIEW_SUBGROUP_CAPACITY = 5;

/** 预计算一个样例的全部特性（含 Cpk/Ppk，纯统计内核，无 DOM）。 */
function previewCharacteristics(spec: SampleDatasetSpec): CharacteristicPreview[] {
  return spec.characteristics.map((plan) => {
    const values = generateSampleValues(plan);
    const specLimits: SpecLimits = {
      usl: plan.usl,
      lsl: plan.lsl,
      target: plan.target ?? (plan.usl + plan.lsl) / 2,
      unit: plan.unit,
    };
    const subgroups = buildSubgroups(
      values.map((value, i) => ({ id: `m${i}`, value, excluded: false })),
      { mode: 'fixed', capacity: PREVIEW_SUBGROUP_CAPACITY },
    );
    const cap = computeCapability(values, specLimits, subgroups, { sigmaMode: 'R' });
    return {
      name: plan.name,
      unit: plan.unit,
      values,
      lsl: plan.lsl,
      usl: plan.usl,
      cpk: cap.cpk,
      ppk: cap.ppk,
      grade: capabilityGrade(cap.cpk),
    };
  });
}

/** 迷你趋势图 option：序列线 + USL/LSL 虚线。 */
function miniChartOption(p: CharacteristicPreview): EChartsOption {
  return {
    grid: { left: 6, right: 6, top: 10, bottom: 4, containLabel: false },
    xAxis: { type: 'category', show: false, data: p.values.map((_, i) => i) },
    yAxis: { type: 'value', show: false, scale: true },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const list = params as Array<{ dataIndex: number }>;
        const idx = Array.isArray(list) && list.length > 0 ? list[0].dataIndex : 0;
        const v = p.values[idx];
        return `#${idx + 1} · ${v} ${p.unit}`;
      },
    },
    series: [
      {
        type: 'line',
        data: p.values,
        symbol: 'none',
        lineStyle: { width: 1.5, color: '#5b8cff' },
        areaStyle: { opacity: 0.08, color: '#5b8cff' },
        markLine: {
          silent: true,
          symbol: 'none',
          label: { show: false },
          lineStyle: { type: 'dashed', color: '#d32f2f', width: 1 },
          data: [{ yAxis: p.usl }, { yAxis: p.lsl }],
        },
      },
    ],
  };
}

/** 单张样例卡片。 */
function SampleCard({ spec }: { spec: SampleDatasetSpec }): ReactElement {
  const navigate = useNavigate();
  const setDataset = useProjectStore((s) => s.setDataset);
  const pushToast = useUiStore((s) => s.pushToast);
  const [busy, setBusy] = useState(false);

  const previews = useMemo(() => previewCharacteristics(spec), [spec]);
  const measurementCount = previews.reduce((acc, p) => acc + p.values.length, 0);
  const defectCount = spec.defects?.length ?? 0;

  /** 载入样例 → 新项目 → 跳能力分析页。 */
  const handleLoad = (): void => {
    const dataset = buildSampleDataset(spec);
    setDataset(dataset);
    pushToast(`已载入样例「${spec.title}」：${dataset.characteristics.length} 个特性 / ${measurementCount} 个测量点`, 'success');
    navigate('/capability');
  };

  const handleDownload = async (): Promise<void> => {
    setBusy(true);
    try {
      await downloadSampleXlsx(spec);
      pushToast(`已生成 ${`hogo-qa-sample-${spec.id}.xlsx`}（结构与导入格式一致，可直接回灌）`, 'success');
    } catch (e) {
      pushToast(`样例下载失败：${e instanceof Error ? e.message : String(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper
      variant="outlined"
      sx={{ p: 2.5, display: 'flex', flexDirection: 'column', gap: 1.5, height: '100%' }}
      data-testid={`sample-card-${spec.id}`}
    >
      <Box>
        <Typography variant="subtitle1" fontWeight={700}>
          {spec.title}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          {spec.scene}
        </Typography>
      </Box>

      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        <Chip size="small" label={`${spec.characteristics.length} 特性`} variant="outlined" />
        <Chip size="small" label={`${measurementCount} 测量点`} variant="outlined" />
        <Chip size="small" label={defectCount > 0 ? `${defectCount} 类不良` : '无不良数据'} variant="outlined" />
      </Stack>

      {/* 特性小图 + Ppk 徽章 */}
      <Stack spacing={1}>
        {previews.map((p) => (
          <Box key={p.name} sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Box sx={{ flexGrow: 1, minWidth: 0 }}>
              <ReactECharts
                option={miniChartOption(p)}
                style={{ height: 56, width: '100%' }}
                notMerge
                lazyUpdate
                opts={{ renderer: 'svg' }}
              />
            </Box>
            <Box sx={{ width: 168, flexShrink: 0 }}>
              <Typography variant="caption" color="text.secondary" noWrap display="block">
                {p.name}（{p.unit}）
              </Typography>
              <Typography variant="body2" fontWeight={700} sx={{ color: GRADE_COLOR[p.grade] ?? GRADE_COLOR.unknown }}>
                Cpk {p.cpk === null ? '—' : p.cpk.toFixed(2)}
                <Typography component="span" variant="body2" sx={{ ml: 0.75, color: 'text.secondary' }} fontWeight={600}>
                  Ppk {p.ppk === null ? '—' : p.ppk.toFixed(2)}
                </Typography>
              </Typography>
              <Typography variant="caption" color="text.disabled" noWrap display="block">
                USL {p.usl} · LSL {p.lsl}
              </Typography>
            </Box>
          </Box>
        ))}
      </Stack>

      {/* 演示要点 */}
      <Box component="ul" sx={{ m: 0, pl: 4, color: 'text.secondary' }}>
        {spec.details.map((d) => (
          <Typography key={d} component="li" variant="body2" sx={{ mb: 0.25 }}>
            {d}
          </Typography>
        ))}
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mt: 'auto', pt: 1 }}>
        <Button
          size="small"
          variant="contained"
          startIcon={<PlayArrowIcon />}
          onClick={handleLoad}
          data-testid={`sample-load-${spec.id}`}
        >
          载入并分析
        </Button>
        <Button
          size="small"
          variant="outlined"
          startIcon={<DownloadIcon />}
          onClick={handleDownload}
          disabled={busy}
          data-testid={`sample-download-${spec.id}`}
        >
          下载 XLSX
        </Button>
      </Box>
    </Paper>
  );
}

/**
 * 渲染样例数据集展示页。
 *
 * @returns 页面元素
 */
export default function SampleDataPage(): ReactElement {
  return (
    <Stack spacing={2.5} data-testid="sample-data-page">
      <Paper variant="outlined" sx={{ p: 2.5, bgcolor: 'action.hover' }}>
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <SparkIcon color="primary" sx={{ mt: 0.5 }} />
          <Box>
            <Typography variant="h6" fontWeight={700}>
              样例数据集
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              内置 3 组合成数据（不含任何真实产线数据），覆盖能力充足 / 临界 / 漂移超差三类典型场景。
              卡片上的每个 Ppk 都由统计内核实时计算，与正式分析同一条代码路径，可用「下载 XLSX」→「数据导入」复算验证。
            </Typography>
          </Box>
        </Stack>
      </Paper>

      <Grid container spacing={2.5} alignItems="stretch">
        {SAMPLE_DATASETS.map((spec) => (
          <Grid item xs={12} md={6} lg={4} key={spec.id} sx={{ display: 'flex' }}>
            <SampleCard spec={spec} />
          </Grid>
        ))}
      </Grid>
    </Stack>
  );
}
