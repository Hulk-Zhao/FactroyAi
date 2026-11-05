/**
 * 导入导出契约类型，来源：
 * - docs/api/contract-freeze.md §25.9（C38 customers）
 * - docs/api/contract-freeze.md §25.10（C39 异步导入）
 * - aps-service ImportResult / ImportJobView / ImportError
 */

/** 同步导入模式；后端 Jackson 按枚举名输出大写。 */
export type ImportMode = 'PREVIEW' | 'EXECUTE'

/** 异步任务状态闭集（C39）。 */
export type ImportJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/** 行级错误：row 为 Excel 物理行号（含表头，从 1 开始）。 */
export interface ImportError {
  row: number
  field: string
  code: number
  message: string
}

/** 同步导入结果（C26/C38）。 */
export interface ImportResult {
  entity: string
  mode: ImportMode
  totalRows: number
  validRows: number
  invalidRows: number
  inserted: number
  updated: number
  skipped: number
  committed: boolean
  replayed: boolean
  errors: ImportError[]
  preview: Record<string, unknown>[]
}

/**
 * 异步任务视图（C39）。
 * 注意：mode=async 返回 202 + 裸对象，不是 Result 信封，前端不得按 data 解包。
 */
export interface ImportJobView {
  jobId: string
  entity: string
  mode: 'async'
  status: ImportJobStatus
  totalRows: number
  processedRows: number
  inserted: number
  updated: number
  skipped: number
  invalidRows: number
  errors: ImportError[]
  committed: boolean
  contentHash: string
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

/** 文件下载结果：保留后端 Content-Disposition 文件名。 */
export interface DownloadFile {
  blob: Blob
  filename: string
}
