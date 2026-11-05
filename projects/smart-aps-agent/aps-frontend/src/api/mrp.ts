import http from './http'
import type { MrpAnalysisParams, MrpAnalysisResult } from '@/types/mrp'

/**
 * 执行 MRP 齐套分析。
 *
 * <p>分析是只读计算，不携带 Idempotency-Key。</p>
 * <p>命名刻意避开 {@code api/materials.ts} 里 M1 遗留的 {@code analyzeMrp}：
 * W8 在 api/index.ts 追加 `export * from './mrp'` 后不会产生重复导出。</p>
 */
export async function runMrpAnalysis(payload: MrpAnalysisParams): Promise<MrpAnalysisResult> {
  const response = await http.post<MrpAnalysisResult>('/mrp/analysis', payload)
  return response.data
}
