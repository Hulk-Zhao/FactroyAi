/**
 * sampleDatasets.spec —— 样例数据集的回归断言。
 *
 * 口径：与数据导入页的样例区（ImportPage）及能力分析页默认口径**完全一致**——
 * 子组容量 5（Xbar-R）+ σ_within 走 R̄/d2；Cpk 用组内 σ、Ppk 用整体 σ。
 * 页面卡片展示的每个数字都由这里的同一条计算路径得出，全部可复算。
 *
 * 基准数字（vite-node 实测，改动生成器/剧本后需同步更新）：
 *   外壳长度  Cpk 1.425(合格) Ppk 0.814   转轴直径  Cpk 1.100(偏低) Ppk 0.884
 *   端面跳动  Cpk 0.883(不合格) Ppk 0.778
 *   焊点拉力  Cpk 1.922(优秀) Ppk 1.624   焊点直径  Cpk 1.391(合格) Ppk 1.305
 *   单件重量  Cpk 0.910(不合格) Ppk 0.434（超 USL 2 点）
 */

import { describe, expect, it } from 'vitest';
import { buildSubgroups, capabilityGrade, computeCapability } from '@/core';
import type { SpecLimits } from '@/core';
import {
  SAMPLE_DATASETS,
  buildSampleDataset,
  getSampleSpec,
} from '../sampleDatasets';
import { generateSampleValues, type SampleCharacteristicPlan } from '../sampleGenerator';

/** 与 ImportPage.PREVIEW_SUBGROUP_CAPACITY 同值。 */
const CAPACITY = 5;

/** 样例区同一条计算路径：固定容量 5 子组 + R 口径 σ_within。 */
function previewCapability(plan: SampleCharacteristicPlan) {
  const values = generateSampleValues(plan);
  const spec: SpecLimits = {
    usl: plan.usl,
    lsl: plan.lsl,
    target: plan.target ?? (plan.usl + plan.lsl) / 2,
    unit: plan.unit,
  };
  const subgroups = buildSubgroups(
    values.map((value, i) => ({ id: `m${i}`, value, excluded: false })),
    { mode: 'fixed', capacity: CAPACITY },
  );
  return { values, cap: computeCapability(values, spec, subgroups, { sigmaMode: 'R' }) };
}

describe('sampleGenerator（确定性）', () => {
  it('同一种子两次生成逐值一致', () => {
    const spec = getSampleSpec('machining-baseline');
    const a = generateSampleValues(spec.characteristics[0]);
    const b = generateSampleValues(spec.characteristics[0]);
    expect(a).toEqual(b);
  });

  it('标准样例「外壳长度」Cpk≈1.43（合格档），Ppk 被阶跃+漂移拉到 0.81', () => {
    const spec = getSampleSpec('machining-baseline');
    const plan = spec.characteristics[0];
    expect(plan.name).toBe('外壳长度');
    expect(plan.name, '剧本顺序被改动时此用例的索引假设失效').toBe(
      SAMPLE_DATASETS[0].characteristics[0].name,
    );
    const { values, cap } = previewCapability(plan);
    expect(values).toHaveLength(50);
    // Cpk（组内 σ）：均值阶跃吃掉余量后仍留在合格档。
    expect(cap.cpk).not.toBeNull();
    expect(cap.cpk!).toBeGreaterThan(1.33);
    expect(cap.cpk!).toBeLessThan(1.67);
    expect(capabilityGrade(cap.cpk)).toBe('qualified');
    // Ppk（整体 σ）：慢漂移 + 阶跃使整体 σ 明显大于组内 σ → 显著劣化。
    expect(cap.ppk).not.toBeNull();
    expect(cap.ppk!).toBeGreaterThan(0.6);
    expect(cap.ppk!).toBeLessThan(1.0);
    // Cp ≠ Pp：这正是相对旧工具的核心修正，两口径必须分离。
    expect(cap.cp! - cap.pp!).toBeGreaterThan(0.3);
  });

  it('标准样例「转轴直径」Cpk≈1.10（偏低档）', () => {
    const spec = getSampleSpec('machining-baseline');
    const plan = spec.characteristics[1];
    const { cap } = previewCapability(plan);
    expect(cap.cpk!).toBeGreaterThan(1.0);
    expect(cap.cpk!).toBeLessThan(1.2);
    expect(capabilityGrade(cap.cpk)).toBe('low');
  });

  it('标准样例「端面跳动」Cpk≈0.88（不合格档），ramp 把尾部推出下限', () => {
    const spec = getSampleSpec('machining-baseline');
    const plan = spec.characteristics[2];
    const { cap } = previewCapability(plan);
    expect(cap.cpk!).toBeGreaterThan(0.7);
    expect(cap.cpk!).toBeLessThan(1.0);
    expect(capabilityGrade(cap.cpk)).toBe('fail');
  });

  it('焊点拉力样例：Cpk 优秀 vs Ppk 合格，双口径差约 0.3（批间慢漂移演示）', () => {
    const spec = getSampleSpec('solder-pull');
    const plan = spec.characteristics[0];
    expect(plan.name).toBe('焊点拉力');
    const { cap } = previewCapability(plan);
    expect(capabilityGrade(cap.cpk)).toBe('excellent');
    expect(cap.cpk!).toBeGreaterThan(1.67);
    expect(cap.ppk!).toBeGreaterThan(1.33);
    expect(cap.ppk!).toBeLessThan(1.67);
    expect(cap.cpk! - cap.ppk!).toBeGreaterThan(0.2);
    expect(cap.cpk! - cap.ppk!).toBeLessThan(0.4);
  });

  it('注塑重量样例确有超 USL 点且 Ppk 显著劣化（判异/超差演示的前提）', () => {
    const spec = getSampleSpec('mold-weight');
    const plan = spec.characteristics[0];
    const { values, cap } = previewCapability(plan);
    const over = values.filter((v) => v > plan.usl);
    expect(over.length).toBeGreaterThanOrEqual(1);
    expect(cap.ppk!).toBeLessThan(1.0);
    expect(capabilityGrade(cap.cpk)).toBe('fail');
  });
});

describe('sampleDatasets', () => {
  it('内置 3 个样例，id 唯一，均有特性与规格', () => {
    expect(SAMPLE_DATASETS).toHaveLength(3);
    const ids = new Set(SAMPLE_DATASETS.map((s) => s.id));
    expect(ids.size).toBe(SAMPLE_DATASETS.length);
    for (const spec of SAMPLE_DATASETS) {
      expect(spec.characteristics.length).toBeGreaterThan(0);
      expect(spec.scene.length).toBeGreaterThan(0);
      expect(spec.details.length).toBeGreaterThan(0);
    }
  });

  it('buildSampleDataset 产出合法 Dataset（每次新 id，可重复载入）', () => {
    const spec = getSampleSpec('solder-pull');
    const d1 = buildSampleDataset(spec);
    const d2 = buildSampleDataset(spec);
    expect(d1.id).not.toBe(d2.id);
    expect(d1.name).toContain('样例');
    expect(d1.sourceType).toBe('json');
    for (const c of d1.characteristics) {
      expect(c.measurements).toHaveLength(50);
      expect(c.specLimits.usl).not.toBeNull();
      expect(c.specLimits.lsl).not.toBeNull();
      expect(c.measurements.every((m) => Number.isFinite(m.value))).toBe(true);
    }
    // 不良记录与 spec 声明一致（柏拉图可演示）。
    expect(d1.defectRecords.map((r) => r.count)).toEqual(spec.defects!.map(([, n]) => n));
  });

  it('全部特性按 Cpk 等级覆盖 ≥3 档（展示徽章颜色有区分度）', () => {
    const grades = new Set(
      SAMPLE_DATASETS.flatMap((spec) =>
        spec.characteristics.map((plan) => capabilityGrade(previewCapability(plan).cap.cpk)),
      ),
    );
    expect(grades.size).toBeGreaterThanOrEqual(3);
  });
});
