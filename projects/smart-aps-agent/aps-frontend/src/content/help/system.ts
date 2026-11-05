import type { HelpModule } from '@/constants/help/types'

const system: HelpModule = {
  'system-users': {
    title: '用户与角色',
    summary: '维护账号状态与角色权限；启用/禁用/锁定会影响登录与接口访问。',
    sections: [
      {
        title: '这一步在做什么',
        body: '维护用户账号与角色权限，决定谁能登录、登录后能访问哪些业务模块。'
      },
      {
        title: '界面上看哪个字段',
        items: [
          '两个页签「用户」「角色与权限」；用户表列：用户名 / 显示名 / 邮箱 / 状态 / 角色 / 版本 / 操作。',
          '「状态」标签显示 ACTIVE、DISABLED、LOCKED；「角色」列是角色码标签。',
          '角色表列：角色码 / 名称 / 说明 / 权限点 / 版本 / 操作。'
        ]
      },
      {
        title: '操作顺序',
        body: '点「新建用户」填用户名与初始密码、勾角色后保存 → 行内「编辑」改状态或重置密码；停用把状态改为 DISABLED。'
      },
      {
        title: '常见错误',
        items: [
          '新建用户不填初始密码，提示「新建用户必须设置初始密码」。',
          '用户名重复或角色不存在，保存失败并展示后端提示，弹窗不关闭。',
          '编辑时用户名是灰的，改不了。',
          '他人已改该用户，保存提示「该用户已被他人修改，请关闭后刷新列表再操作」。'
        ]
      }
    ]
  },
  'system-audit': {
    title: '审计日志',
    summary: '只读查询关键写操作与登录事件，用于追溯与排障。',
    sections: [
      {
        title: '这一步在做什么',
        body: '按时间、操作人和资源查关键写操作与登录事件，回答「谁在何时改了什么」。'
      },
      {
        title: '界面上看哪个字段',
        items: [
          '筛选区字段：action、resourceType、resourceId、userId、from（含）、to（不含）。',
          '列表列：id (int64) / 时间 / action / resourceType / resourceId / userId / clientIp。',
          '展开行显示 traceId、beforeValue、afterValue。'
        ]
      },
      {
        title: '操作顺序',
        body: '选 action 与 resourceType → 填 resourceId / userId → 选 from、to → 点「查询」；查错了点「重置」回到默认范围。'
      },
      {
        title: '常见错误',
        items: [
          'from 晚于或等于 to，页面提示「开始时间必须早于结束时间（区间为 [from, to)）」并清空列表。',
          '把 to 当成包含，实际不含该时刻，边界记录会查不到。',
          'action 与 resourceType 是 AND 组合，筛太细会得到「当前筛选下暂无审计记录」。',
          '审计记录没有编辑/删除按钮，改数据必须回业务页操作。'
        ]
      }
    ]
  }
}

export default system
