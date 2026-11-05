import {
  ElAlert,
  ElAside,
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElCol,
  ElContainer,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDivider,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPageHeader,
  ElPagination,
  ElProgress,
  ElRadio,
  ElResult,
  ElRow,
  ElSelect,
  ElSkeleton,
  ElSkeletonItem,
  ElStep,
  ElSteps,
  ElSubMenu,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeSelect,
  ElTooltip,
  ElTree,
  ElUpload
} from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 暗色变量（html.dark 作用域）；本项目的 main.css 在其后加载并逐一改写为自身 token。
import 'element-plus/theme-chalk/dark/css-vars.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import http from './api/http'
import App from './App.vue'
import { installMockIfEnabled } from './mocks'
import router from './router'
import './styles/main.css'

installMockIfEnabled(http)

// M3 性能整改：按实际使用的 49 个组件显式注册，替代全量 ElementPlus，
// 让未使用的组件与 locale 在打包阶段被摇树，避免首屏为整库买单。
const COMPONENTS = [
  ElAlert,
  ElAside,
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElCol,
  ElContainer,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDivider,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElInput,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPageHeader,
  ElPagination,
  ElProgress,
  ElRadio,
  ElResult,
  ElRow,
  ElSelect,
  ElSkeleton,
  ElSkeletonItem,
  ElStep,
  ElSteps,
  ElSubMenu,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeSelect,
  ElTooltip,
  ElTree,
  ElUpload
]

const app = createApp(App).use(createPinia()).use(router)
COMPONENTS.forEach((component) => app.use(component))
// v-loading 指令由 Loading 插件注册。
app.use(ElLoading)
app.mount('#app')
