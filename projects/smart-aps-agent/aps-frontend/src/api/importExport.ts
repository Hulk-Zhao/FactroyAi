import http, { createIdempotencyKey } from './http'
import type { DownloadFile, ImportJobView, ImportResult } from '@/types/import'

const XLSX_FALLBACK_FILENAME = 'import.xlsx'

function decodeFilename(value: string): string {
  const trimmed = value.trim().replace(/^"|"$/g, '')
  try {
    return decodeURIComponent(trimmed)
  } catch {
    return trimmed
  }
}

/** 解析 Content-Disposition，优先 RFC 5987 的 filename*，其次普通 filename。 */
export function parseContentDisposition(disposition?: string): string {
  if (!disposition) {
    return ''
  }
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded?.[1]) {
    return decodeFilename(encoded[1])
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  return plain?.[1] ? decodeFilename(plain[1]) : ''
}

function readHeader(response: { headers: Record<string, unknown> }, name: string): string {
  const value = response.headers[name.toLowerCase()] ?? response.headers[name]
  return typeof value === 'string' ? value : ''
}

/** 下载导入模板：保留后端文件名（Content-Disposition / X-Template-Filename）。 */
export async function downloadImportTemplate(entity: string): Promise<DownloadFile> {
  const response = await http.get<Blob>(`/${encodeURIComponent(entity)}/import/template`, {
    responseType: 'blob',
    timeout: 60_000
  })
  const filename =
    parseContentDisposition(readHeader(response, 'content-disposition')) ||
    decodeFilename(readHeader(response, 'x-template-filename')) ||
    `${entity}-import-template.xlsx`
  return { blob: response.data, filename: filename || XLSX_FALLBACK_FILENAME }
}

/** 导出实体数据：列顺序与导入模板一致，保留后端文件名。 */
export async function downloadEntityExport(entity: string): Promise<DownloadFile> {
  const response = await http.get<Blob>(`/${encodeURIComponent(entity)}/export`, {
    responseType: 'blob',
    timeout: 60_000
  })
  const filename =
    parseContentDisposition(readHeader(response, 'content-disposition')) ||
    `${entity}-export.xlsx`
  return { blob: response.data, filename: filename || XLSX_FALLBACK_FILENAME }
}

function buildFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

/** 预览导入：mode=preview，只校验不落库。 */
export async function previewImport(entity: string, file: File): Promise<ImportResult> {
  const response = await http.post<ImportResult>(`/${encodeURIComponent(entity)}/import`, buildFormData(file), {
    params: { mode: 'preview' }
  })
  return response.data
}

/** 执行导入：mode=execute，全量通过才落库；token 用于幂等重放。 */
export async function executeImport(
  entity: string,
  file: File,
  token?: string
): Promise<ImportResult> {
  const response = await http.post<ImportResult>(`/${encodeURIComponent(entity)}/import`, buildFormData(file), {
    params: { mode: 'execute' },
    headers: token ? { 'X-Import-Token': token } : undefined
  })
  return response.data
}

/**
 * 异步受理：mode=async，后端返回 202 + 裸 ImportJobView（非 Result 信封）。
 * http 拦截器只在存在 {code,message} 时才解包，因此这里拿到的就是任务视图本身。
 */
export async function submitAsyncImport(
  entity: string,
  file: File,
  token?: string
): Promise<ImportJobView> {
  const response = await http.post<ImportJobView>(
    `/${encodeURIComponent(entity)}/import`,
    buildFormData(file),
    {
      params: { mode: 'async' },
      headers: token ? { 'X-Import-Token': token } : undefined,
      timeout: 60_000
    }
  )
  return response.data
}

/** 查询异步任务：同样返回裸 ImportJobView。 */
export async function getImportJob(entity: string, jobId: string): Promise<ImportJobView> {
  const response = await http.get<ImportJobView>(
    `/${encodeURIComponent(entity)}/import/jobs/${encodeURIComponent(jobId)}`
  )
  return response.data
}

/** 取消异步任务：仅 PENDING/RUNNING 可取消，终态后端返回 40901。 */
export async function cancelImportJob(entity: string, jobId: string): Promise<ImportJobView> {
  const response = await http.post<ImportJobView>(
    `/${encodeURIComponent(entity)}/import/jobs/${encodeURIComponent(jobId)}/cancel`
  )
  return response.data
}

/** 生成 8-128 位幂等令牌（异步/执行模式使用）。 */
export function createImportToken(entity: string): string {
  return createIdempotencyKey(`imp-${entity}`)
}
