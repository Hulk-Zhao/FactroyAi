import type { RouterModule } from '../types'

import { dashboardModule } from './dashboard'
import { ganttModule } from './gantt'
import { ganttOrderModule } from './gantt-order'
import { importModule } from './import'
import { masterDataModule } from './master-data'
import { mrpModule } from './mrp'
import { ordersModule } from './orders'
import { plansModule } from './plans'
import { processModule } from './process'
import { sandboxModule } from './sandbox'
import { solveModule } from './solve'
import { strategiesModule } from './strategies'
import { systemModule } from './system'

/**
 * 唯一新增路由的追加点：FE2/FE5/FE6 在此追加 import 与数组项，
 * 不要修改 src/router/index.ts。
 */
export const routerModules: RouterModule[] = [
  dashboardModule,
  masterDataModule,
  processModule,
  ordersModule,
  strategiesModule,
  mrpModule,
  solveModule,
  ganttModule,
  ganttOrderModule,
  importModule,
  plansModule,
  sandboxModule,
  systemModule
]

export const menuModules: RouterModule[] = [...routerModules]
  .filter((module) => module.menu !== false)
  .sort((left, right) => left.order - right.order)
