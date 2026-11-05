/**
 * 统一返回体与分页结构。
 * 契约来源：docs/api/conventions.md 第 2、4 节。
 */
export interface Result<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/** 兼容旧命名；新代码请直接使用 Result<T>。 */
export type ApiResponse<T> = Result<T>

/** 分页出参：items/page/size/total/totalPages。 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export type SortOrder = 'asc' | 'desc'

/** sort 支持单字段（`field,asc`）或多字段数组（序列化为逗号分隔）。 */
export type SortSpec = string | string[]

/** 分页入参：page 从 1 开始，size 默认 20、上限 200。 */
export interface PageQuery {
  page?: number
  size?: number
  sort?: SortSpec
}

/** 通用查询参数，避免各域重复定义索引签名。 */
export type QueryParams = Record<string, string | number | boolean | undefined | null>
