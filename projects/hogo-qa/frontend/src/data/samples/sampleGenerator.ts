/**
 * sampleGenerator —— 样例数据集的确定性合成生成器。
 *
 * 与 `frontend/samples/make-sample.mjs` 同一套机制（mulberry32 + Box-Muller）：
 * 固定种子 → 任何环境任何时间生成逐字节一致的序列，页面上展示的所有统计量
 * （Ppk / 判异命中数 / 柏拉图占比）都可以被独立复算。
 *
 * 「剧本」字段含义（与 make-sample.mjs 保持同一词汇）：
 *   base    标称中心；noise   子组内噪声 σ（→ σ_within → Cp/Cpk）
 *   drift   每 10 点叠加的慢漂移（→ σ_overall → Pp/Ppk）
 *   shift   整段均值阶跃 { at, delta }；ramp 连续上升 { at, step, points }
 *   spikes  单点尖峰 { 下标: 偏移量 }
 */

/** 单个特性的「剧本」。 */
export interface SampleCharacteristicPlan {
  /** 特性名（导入格式里叫「物料名称」）。 */
  name: string;
  unit: string;
  decimals: number;
  lsl: number;
  usl: number;
  /** 目标值；缺省取规格中心。 */
  target?: number;
  base: number;
  noise: number;
  drift: number;
  shift: { at: number; delta: number } | null;
  ramp: { at: number; step: number; points: number } | null;
  spikes: Record<number, number>;
  /** 随机种子（固定 → 可复算）。 */
  seed: number;
}

/** mulberry32：确定性 PRNG，返回 [0,1)。 */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 标准正态（Box-Muller），输入为 [0,1) 均匀随机源。 */
export function makeNormal(rnd: () => number): () => number {
  return () => {
    let u = 0;
    while (u === 0) u = rnd();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rnd());
  };
}

/** 每特性测量点数（与仓库标准样例 xlsx 一致）。 */
export const SAMPLE_POINT_COUNT = 50;

/** 按「剧本」生成一个特性的测量值序列（确定性）。 */
export function generateSampleValues(plan: SampleCharacteristicPlan): number[] {
  const rnd = mulberry32(plan.seed);
  const normal = makeNormal(rnd);
  const round = (v: number): number => Number(v.toFixed(plan.decimals));
  const values: number[] = [];
  for (let i = 0; i < SAMPLE_POINT_COUNT; i += 1) {
    let v = plan.base + normal() * plan.noise;
    v += Math.floor(i / 10) * plan.drift;
    if (plan.shift && i >= plan.shift.at) {
      v += plan.shift.delta;
    }
    if (plan.ramp && i >= plan.ramp.at && i < plan.ramp.at + plan.ramp.points) {
      v += (i - plan.ramp.at + 1) * plan.ramp.step;
    }
    if (plan.spikes[i] !== undefined) {
      v += plan.spikes[i];
    }
    values.push(round(v));
  }
  return values;
}
