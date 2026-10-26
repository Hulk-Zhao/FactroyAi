/**
 * sampleDatasets —— 内置样例数据集（合成数据，可复算）。
 *
 * 为什么内置在产物里而不是请求后端：样例页是「一键体验」入口，
 * 离线包（file://）也要能加载；后端 /api/samples 提供的是仓库标准
 * xlsx 文件的下载通道，两者互补。
 *
 * 数据全部为合成数据（不含真实产线数据）；统计口径由 `@/core` 实时计算，
 * 页面展示的每个 Ppk 都可复算（见 sampleDatasets.spec.ts 的回归断言）。
 */

import { genId } from '@/data/internal';
import type { Dataset, DefectRecord, Characteristic, Measurement } from '@/data/schema';
import type { SpecLimits } from '@/core';
import {
  generateSampleValues,
  SAMPLE_POINT_COUNT,
  type SampleCharacteristicPlan,
} from './sampleGenerator';

/** 样例数据集元信息。 */
export interface SampleDatasetSpec {
  id: string;
  title: string;
  /** 一句话场景（卡片副标题）。 */
  scene: string;
  /** 演示要点（卡片 bullet）。 */
  details: string[];
  tags: string[];
  characteristics: SampleCharacteristicPlan[];
  /** 不良记录（柏拉图演示用）；null 表示该样例不含不良数据。 */
  defects: Array<[type: string, count: number]> | null;
}

/** 标准样例：与仓库 `hogo-qa-sample.xlsx` 同一剧本（同种子），数字可复算互核。 */
const MACHINING: SampleDatasetSpec = {
  id: 'machining-baseline',
  title: '标准样例 · 机加工三特性',
  scene: '一份数据同时演示双 σ 口径、判异命中与柏拉图——与仓库标准样例 xlsx 同剧本同种子。',
  details: [
    '三条特性 Cpk 分属 合格 / 偏低 / 不合格 三档（子组容量 5，Xbar-R）',
    '慢漂移 + 均值阶跃把 Ppk 拉低 0.3~0.6 —— Cp≠Pp 正是相对旧工具的核心修正',
    '6 类不良构成越过 80% 分界线的柏拉图曲线，控制图判异真实命中',
  ],
  tags: ['SPC', '判异准则', '柏拉图', '能力分析'],
  characteristics: [
    {
      name: '外壳长度',
      unit: 'mm',
      decimals: 4,
      lsl: 49.8,
      usl: 50.2,
      target: 50.0,
      base: 50.0,
      noise: 0.03,
      drift: 0.012,
      shift: { at: 25, delta: 0.055 },
      ramp: null,
      spikes: { 17: 0.11 },
      seed: 20260919,
    },
    {
      name: '转轴直径',
      unit: 'mm',
      decimals: 4,
      lsl: 11.98,
      usl: 12.02,
      target: 12.0,
      base: 12.0,
      noise: 0.005,
      drift: 0.0015,
      shift: null,
      ramp: null,
      spikes: {},
      seed: 20260920,
    },
    {
      name: '端面跳动',
      unit: 'mm',
      decimals: 4,
      lsl: 0.02,
      usl: 0.08,
      target: 0.05,
      base: 0.0535,
      noise: 0.0092,
      drift: 0.0022,
      shift: null,
      ramp: { at: 30, step: 0.0035, points: 6 },
      spikes: {},
      seed: 20260921,
    },
  ],
  defects: [
    ['毛刺', 42],
    ['尺寸超差', 27],
    ['划伤', 18],
    ['变形', 9],
    ['气孔', 6],
    ['其他', 5],
  ],
};

/** 焊点拉力：批间慢漂移使 Cpk 与 Ppk 显著分离（σ_within ≪ σ_overall）。 */
const SOLDER_PULL: SampleDatasetSpec = {
  id: 'solder-pull',
  title: '焊点拉力 · 双 σ 口径',
  scene: 'Cpk 优秀、Ppk 落到合格档：批间慢漂移吃掉整体能力余量，两个口径差约 0.3。',
  details: [
    '组内噪声小、每 10 点 +0.55N 的批间慢漂移 → σ_within ≪ σ_overall',
    '同一序列两种口径：Cp 2.31 vs Pp 1.95、Cpk 1.92 vs Ppk 1.62（焊点拉力）',
    '建议演示路径：能力分析双 σ 对比 → 控制图观察缓慢爬升',
  ],
  tags: ['Cpk vs Ppk', '漂移', '能力分析'],
  characteristics: [
    {
      name: '焊点拉力',
      unit: 'N',
      decimals: 2,
      lsl: 45,
      usl: 60,
      target: 52.5,
      base: 52.5,
      noise: 1.2,
      drift: 0.55,
      shift: null,
      ramp: null,
      spikes: {},
      seed: 20261026,
    },
    {
      name: '焊点直径',
      unit: 'mm',
      decimals: 3,
      lsl: 0.7,
      usl: 0.9,
      target: 0.8,
      base: 0.803,
      noise: 0.022,
      drift: 0.002,
      shift: null,
      ramp: null,
      spikes: {},
      seed: 20261027,
    },
  ],
  defects: [
    ['虚焊', 18],
    ['偏移', 11],
    ['锡珠', 7],
    ['连锡', 4],
    ['其他', 3],
  ],
};

/** 注塑重量：均值持续上漂 + 阶跃出界，判异连片命中。 */
const MOLD_WEIGHT: SampleDatasetSpec = {
  id: 'mold-weight',
  title: '注塑件重量 · 漂移与超差',
  scene: '均值缓慢上漂并在中段阶跃出界：判异准则连片命中、2 点超 USL、Ppk 显著劣化。',
  details: [
    '阶跃后测量值贴近并越过 USL（超 USL 2 点），控制图判异应连片命中',
    '能力页可直接看到 Ppk=0.43 的劣化，以及「排除异常值」交互的改善',
    '建议演示路径：控制图判异 → 数据表定位 → 能力复查',
  ],
  tags: ['控制图', '判异', '超差', '柏拉图'],
  characteristics: [
    {
      name: '单件重量',
      unit: 'g',
      decimals: 3,
      lsl: 24.5,
      usl: 25.5,
      target: 25.0,
      base: 25.0,
      noise: 0.09,
      drift: 0.032,
      shift: { at: 20, delta: 0.3 },
      ramp: null,
      spikes: { 8: 0.3 },
      seed: 20261028,
    },
  ],
  defects: [
    ['缺料', 15],
    ['飞边', 9],
    ['缩水', 6],
    ['色差', 4],
  ],
};

/** 全部内置样例（顺序即页面展示顺序）。 */
export const SAMPLE_DATASETS: SampleDatasetSpec[] = [MACHINING, SOLDER_PULL, MOLD_WEIGHT];

/** 按 id 取样例；未找到抛错（调用方都是内部固定 id）。 */
export function getSampleSpec(id: string): SampleDatasetSpec {
  const found = SAMPLE_DATASETS.find((s) => s.id === id);
  if (!found) {
    throw new Error(`未找到样例数据集：${id}`);
  }
  return found;
}

/**
 * 把样例「剧本」构建为应用领域 Dataset 实体（结构与 importer/buildModel 产物一致）。
 *
 * 每次调用都生成全新 id / 时间戳：走 projectStore.setDataset 的「导入 = 新项目」语义，
 * 同一样例可反复载入形成多个项目，互不覆盖。
 */
export function buildSampleDataset(spec: SampleDatasetSpec): Dataset {
  const datasetId = genId('ds');
  const projectId = 'pending';
  const importedAt = new Date().toISOString();

  const characteristics: Characteristic[] = spec.characteristics.map((plan) => {
    const charId = genId('char');
    const values = generateSampleValues(plan);
    const measurements: Measurement[] = values.map((value) => ({
      id: genId('meas'),
      characteristicId: charId,
      value,
      subgroupId: null,
      timestamp: null,
      batch: null,
      excluded: false,
      excludeReason: null,
    }));
    const specLimits: SpecLimits = {
      usl: plan.usl,
      lsl: plan.lsl,
      target: plan.target ?? (plan.usl + plan.lsl) / 2,
      unit: plan.unit,
    };
    return {
      id: charId,
      datasetId,
      name: plan.name,
      specLimits,
      measurements,
      subgroups: [],
      nullCount: 0,
      outlierFlags: [],
      preprocessConfigRef: null,
      measurementBlobRef: null,
    };
  });

  const defectRecords: DefectRecord[] = (spec.defects ?? []).map(([defectType, count]) => ({
    id: genId('def'),
    datasetId,
    defectType,
    count,
    category: null,
  }));

  return {
    id: datasetId,
    projectId,
    name: `样例 · ${spec.title}`,
    sourceType: 'json',
    importedAt,
    rawFileName: `${spec.id}.json`,
    characteristics,
    defectRecords,
  };
}

/**
 * 生成样例 xlsx 并触发浏览器下载。
 *
 * 表结构与仓库标准样例一致（`尺寸数据` + `不良数据`），产物可直接再走
 * 「数据导入」页回灌——页面展示与文件下载说的是同一份数据。
 */
export async function downloadSampleXlsx(spec: SampleDatasetSpec): Promise<void> {
  const XLSX = await import('xlsx');
  const dimension: Array<Array<string | number>> = [
    ['物料名称', '测量值', 'USL', 'LSL', '单位'],
  ];
  for (const plan of spec.characteristics) {
    for (const v of generateSampleValues(plan)) {
      dimension.push([plan.name, v, plan.usl, plan.lsl, plan.unit]);
    }
  }
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(dimension), '尺寸数据');
  if (spec.defects) {
    const defect: Array<Array<string | number>> = [['不良类型', '不良数量'], ...spec.defects];
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(defect), '不良数据');
  }
  XLSX.writeFile(wb, `hogo-qa-sample-${spec.id}.xlsx`, { bookType: 'xlsx' });
}

/** 样例测量点数（供展示页文案）。 */
export { SAMPLE_POINT_COUNT };
