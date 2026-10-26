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
  /** AI 助手回复样例（离线模式展示用）。 */
  aiExamples: Array<{ question: string; answer: string }>;
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
  aiExamples: [
    {
      question: '请对当前数据做全面诊断',
      answer: '## 全面诊断报告\n\n### 1. 能力分析\n- **外壳长度**：Cpk=1.12（偏低），Ppk=0.78（不合格）。第 25 点后均值阶跃 +0.055mm，导致 Ppk 显著低于 Cpk，说明存在特殊原因变差。\n- **转轴直径**：Cpk=2.67（优秀），Ppk=2.51（优秀）。过程稳定且能力充足。\n- **端面跳动**：Cpk=0.82（不合格），Ppk=0.65（不合格）。第 30 点后斜坡漂移使均值上移，能力严重不足。\n\n### 2. 控制图判异\n- 外壳长度：Nelson Rule 1（第 17 点超 USL）、Rule 2（连续 9 点偏移）命中\n- 端面跳动：Rule 1（2 点超 USL）、Rule 5（连续 3 点中 2 点在 Zone B 或以外）命中\n\n### 3. 柏拉图分析\n- 不良 TOP3：毛刺(42)、尺寸超差(27)、划伤(18)，累计占比 79.3%\n- 建议优先解决毛刺问题，可消除近半数不良\n\n### 4. 改善建议\n1. 排查外壳长度第 25 点后的工序变更（换刀/夹具调整），恢复均值中心\n2. 端面跳动需检查夹具磨损或定位面清洁，第 30 点后漂移趋势需立即干预\n3. 毛刺问题建议增加去毛刺工序或优化切削参数',
    },
    {
      question: '解读一下外壳长度的控制图',
      answer: '## 控制图解读 · 外壳长度\n\n### Xbar-R 图要点\n- **UCL = 50.083mm，LCL = 49.917mm，CL = 50.0mm**\n- 第 1-24 点：均值在中心线附近波动，过程受控\n- 第 25 点起：均值阶跃至 ~50.055mm，连续 9 点位于中心线上方（Nelson Rule 2 命中）\n- 第 17 点：出现异常尖峰（+0.11mm），接近 UCL\n\n### R 图要点\n- 极差整体稳定，未触发极差异常\n- 组内变差未扩大，问题出在均值偏移而非散布增加\n\n### 结论\n这是典型的「均值阶跃」型异常，建议排查第 25 点对应时间段的工序变更记录。',
    },
    {
      question: '有什么改善建议？',
      answer: '## 改善建议\n\n### 优先级 P0：端面跳动漂移\n- 第 30 点后斜坡漂移使 Ppk 降至 0.65，已有 2 点超 USL\n- **立即措施**：检查夹具定位面清洁度与磨损状态，必要时更换定位销\n- **短期措施**：增加首末件检验频率，设置漂移预警阈值\n\n### 优先级 P1：外壳长度均值偏移\n- 第 25 点后均值偏移 0.055mm，Cpk 从 1.8 降至 1.12\n- **措施**：排查换刀记录或夹具调整，恢复刀具补偿值\n\n### 优先级 P2：毛刺不良治理\n- 毛刺占不良 42/107 = 39.3%，是最大不良项\n- **措施**：优化切削速度与进给量，评估增加去毛刺工序的经济性',
    },
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
  aiExamples: [
    {
      question: 'Cpk 和 Ppk 为什么差这么多？',
      answer: '## Cpk vs Ppk 差异分析\n\n### 数据概览\n- **焊点拉力**：Cpk=1.92（优秀），Ppk=1.62（合格），差值 0.30\n- **焊点直径**：Cpk=2.41（优秀），Ppk=2.28（优秀），差值 0.13\n\n### 根因：批间慢漂移\n焊点拉力的每 10 个测量点存在 +0.55N 的批间漂移：\n- **组内变差（σ_within）**：仅反映随机噪声 1.2N，故 Cpk 偏高\n- **整体变差（σ_overall）**：包含批间漂移累积，故 Ppk 偏低\n- Cp=2.31 vs Pp=1.95，同样验证了这一结论\n\n### 实际影响\n- 短期看过程能力充足（Cpk 口径）\n- 长期看若漂移持续，Ppk 会进一步恶化，最终可能跌出合格档\n\n### 建议\n1. 监控批次间均值变化趋势，设置漂移预警\n2. 评估是否需要增加批次间校准或补偿机制\n3. 报告时同时展示双 σ 口径，避免单看 Cpk 高估过程能力',
    },
    {
      question: '请对当前数据做全面诊断',
      answer: '## 全面诊断报告\n\n### 1. 能力分析\n- **焊点拉力**：Cpk=1.92（优秀），Ppk=1.62（合格）。批间慢漂移导致双 σ 口径分离 0.30，长期能力余量偏紧。\n- **焊点直径**：Cpk=2.41（优秀），Ppk=2.28（优秀）。过程稳定，能力充足。\n\n### 2. 控制图判异\n- 焊点拉力：未触发 Nelson 规则，但 Xbar 图可见缓慢上升趋势\n- 焊点直径：过程受控，无异常\n\n### 3. 柏拉图分析\n- 不良 TOP3：虚焊(18)、偏移(11)、锡珠(7)，累计占比 78.6%\n- 虚焊为首要不良，建议排查焊接温度与时间参数\n\n### 4. 改善建议\n1. 焊点拉力的批间漂移需关注，建议每批次记录均值并绘制趋势图\n2. 虚焊问题建议检查回流焊温度曲线，特别是预热区温度是否充分\n3. 当前双 σ 口径差异 0.30，建议在质量报告中同时呈现，避免高估过程能力',
    },
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
  aiExamples: [
    {
      question: '请对当前数据做全面诊断',
      answer: '## 全面诊断报告\n\n### 1. 能力分析\n- **单件重量**：Cpk=0.71（不合格），Ppk=0.43（不合格）。过程能力严重不足，均值持续上漂并在第 20 点阶跃出界。\n- 超出 USL 的测量点：2 个（第 8 点尖峰 +0.3g，第 20 点后阶跃区域）\n\n### 2. 控制图判异\n- Nelson Rule 1：2 点超控制限（USL）\n- Nelson Rule 2：第 20 点后连续 9 点位于中心线上方\n- Nelson Rule 5：连续 3 点中 2 点在 Zone B 或以外\n- 判异连片命中，过程已失控\n\n### 3. 柏拉图分析\n- 不良 TOP2：缺料(15)、飞边(9)，累计占比 70.8%\n- 飞边与重量超差直接相关，指向同一根因\n\n### 4. 改善建议\n1. **立即停机检查**：第 20 点后的阶跃表明注塑参数或模具状态突变，需排查注射压力与保压时间\n2. 排查第 8 点尖峰：可能是单模腔异物或短暂压力波动\n3. 飞边与超差同源，解决漂移后两类不良应同时下降\n4. 建议在排除第 20 点后异常值后复查 Ppk，评估过程真实能力',
    },
    {
      question: '解读一下控制图',
      answer: '## 控制图解读 · 单件重量\n\n### Xbar 图要点\n- **UCL = 25.27g，LCL = 24.73g，CL = 25.0g**\n- 第 1-7 点：均值在中心线附近，过程受控\n- 第 8 点：异常尖峰（+0.3g），接近 UCL，可能是单次异常\n- 第 9-19 点：均值缓慢上漂（drift=0.032g/点），趋势明显\n- 第 20 点起：阶跃 +0.3g，此后连续 9 点位于中心线上方（Rule 2 命中）\n- 第 20 点后：2 点超出 USL(25.5g)，过程失控\n\n### R 图要点\n- 极差在第 8 点和第 20 点有所增大，但整体未触发极差异常\n- 组内散布未发生质变，问题集中在均值漂移\n\n### 结论\n典型的「慢漂移 + 阶跃」复合异常。第 8 点是随机尖峰，第 20 点是工序突变。建议分别排查：第 8 点查单次操作记录，第 20 点查设备参数变更。',
    },
    {
      question: '有什么改善建议？',
      answer: '## 改善建议\n\n### 优先级 P0：均值阶跃（第 20 点）\n- 阶跃 +0.3g 直接导致 2 点超 USL，Ppk 降至 0.43\n- **立即措施**：停机检查注塑机保压压力、注射速度、模具温度\n- **短期措施**：恢复参数后连续监控 10 点，确认均值回归中心\n\n### 优先级 P1：慢漂移趋势\n- drift=0.032g/点，50 点累积漂移 1.6g，远超公差带\n- **措施**：检查螺杆磨损情况，评估是否需要更换螺杆头\n- **预防**：设置漂移预警（连续 6 点同向趋势即报警）\n\n### 优先级 P2：飞边不良\n- 飞边(9)与超差同源，解决漂移后应同步改善\n- 若飞边仍存在，检查锁模力是否充足',
    },
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
