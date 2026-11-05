/**
 * boms 域类型，字段对齐 docs/api/openapi/materials.yaml 的 BOM schemas；
 * 路径 ID 语义按 contract-freeze C6：BOM 使用 UUID。
 */

export type BomStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE'

export interface BomItem {
  materialCode: string
  qtyPerUnit: number
  scrapRate?: number
  position?: string
  substitutes?: string[]
  isPhantom?: boolean
}

export interface Bom {
  id: string
  parentMaterialCode: string
  bomVersion: string
  effectiveDate: string
  expiryDate?: string | null
  status: BomStatus
  isPanel?: boolean
  panelSize?: number | null
  items: BomItem[]
  version: number
}

export interface BomCreateRequest {
  parentMaterialCode: string
  bomVersion: string
  effectiveDate: string
  expiryDate?: string | null
  isPanel?: boolean
  panelSize?: number | null
  items: BomItem[]
}

export interface BomTreeNode {
  /** 展开到最深层或子件无 ACTIVE BOM 时，后端不返回 bomId。 */
  bomId?: string
  parentMaterialCode: string
  materialName: string
  qtyPerUnit: number
  level: number
  children?: BomTreeNode[]
}

/** BOM 列表筛选；parentMaterialCode/status 为前端兼容性扩展参数。 */
export interface BomQuery {
  parentMaterialCode?: string
  status?: BomStatus
}
