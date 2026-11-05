/**
 * 表单校验通用规则（Element Plus）。
 * 约定：业务代码统一使用本文件规则，保证提示文案、触发时机一致。
 */
import type { FormItemRule } from 'element-plus'

export function required(message = '该字段为必填项'): FormItemRule {
  return { required: true, message, trigger: ['blur', 'change'] }
}

export function maxLength(limit: number, message?: string): FormItemRule {
  return {
    max: limit,
    message: message ?? `长度不能超过 ${limit} 个字符`,
    trigger: ['blur', 'change']
  }
}

export function pattern(regexp: RegExp, message: string): FormItemRule {
  return { pattern: regexp, message, trigger: ['blur', 'change'] }
}

/** 业务编码：字母/数字/下划线/中划线，1-64 位。 */
export function businessCode(message = '仅支持字母、数字、下划线和中划线'): FormItemRule {
  return pattern(/^[A-Za-z0-9_-]{1,64}$/, message)
}

export function positiveNumber(message = '必须大于 0'): FormItemRule {
  return {
    validator: (_rule, value, callback) => {
      if (value === undefined || value === null || value === '') {
        callback()
        return
      }
      if (typeof value !== 'number' || Number.isNaN(value) || value <= 0) {
        callback(new Error(message))
        return
      }
      callback()
    },
    trigger: ['blur', 'change']
  }
}

export function nonNegativeNumber(message = '不能小于 0'): FormItemRule {
  return {
    validator: (_rule, value, callback) => {
      if (value === undefined || value === null || value === '') {
        callback()
        return
      }
      if (typeof value !== 'number' || Number.isNaN(value) || value < 0) {
        callback(new Error(message))
        return
      }
      callback()
    },
    trigger: ['blur', 'change']
  }
}

/** ISO-8601 UTC 时间字符串（契约要求带 Z 偏移）。 */
export function isoUtcDateTime(message = '请提供 ISO-8601 UTC 时间'): FormItemRule {
  return pattern(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/, message)
}

/** 纯日期 yyyy-MM-dd。 */
export function isoDate(message = '日期格式应为 yyyy-MM-dd'): FormItemRule {
  return pattern(/^\d{4}-\d{2}-\d{2}$/, message)
}
