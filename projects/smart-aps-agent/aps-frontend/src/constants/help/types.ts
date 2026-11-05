/** 场景帮助内容结构：一个场景固定四段（定位 / 关键字段 / 操作顺序 / 常见错误）。 */
export interface HelpSection {
  title: string
  body?: string
  items?: string[]
}

export interface HelpScene {
  /** 场景名，展示在帮助面板标题 */
  title: string
  /** 一句话定位 */
  summary: string
  sections: HelpSection[]
}

/** 每个帮助内容模块以路由 name 为键导出场景集合。 */
export type HelpModule = Record<string, HelpScene>
