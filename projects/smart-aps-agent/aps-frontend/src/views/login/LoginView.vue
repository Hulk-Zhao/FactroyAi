<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { ApiError } from '@/api/http'
import { login } from '@/api/system'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import { isMockEnabled } from '@/mocks'

const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const mockEnabled = isMockEnabled()

/**
 * C30 错误文案映射（以实际后端为准）：
 * - 40100：用户名或密码错误；后端对「密码错误 / 账号 DISABLED / 账号 LOCKED」统一返回 40100，
 *   M3 不返回 423（这里保留 423 的防御分支，避免后端未来变更时直接落到“未知错误”）。
 * - 40300：权限不足（已登录但无权限访问）。
 * - 42900：请求过于频繁。
 */
function describeLoginError(exception: unknown): string {
  if (exception instanceof ApiError) {
    switch (exception.code) {
      case 40100:
        return '用户名或密码错误。若连续失败导致账号锁定，请 15 分钟后重试。'
      case 40300:
        return '权限不足：当前账号没有登录权限，请联系管理员。'
      case 42900:
        return '请求过于频繁，请稍后再试。'
      case 40000:
        return exception.message || '用户名和密码不能为空。'
      default:
        if (exception.httpStatus === 423) {
          return '账号已锁定，请 15 分钟后重试。'
        }
        return exception.message || '登录失败，请稍后重试。'
    }
  }
  return exception instanceof Error ? exception.message : '登录失败，请稍后重试。'
}

const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0)

async function onSubmit() {
  errorMessage.value = ''
  if (!canSubmit.value) {
    errorMessage.value = '请输入用户名和密码。'
    return
  }
  submitting.value = true
  try {
    const result = await login({ username: username.value.trim(), password: password.value })
    ElMessage.success(`登录成功，令牌有效期 ${Math.round(result.expiresIn / 3600)} 小时`)
    await router.replace('/')
  } catch (exception) {
    errorMessage.value = describeLoginError(exception)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (router.currentRoute.value.query.redirected === '1') {
    errorMessage.value = '登录已过期，请重新登录。'
  }
})
</script>

<template>
  <div class="login-page">
    <div class="login-theme">
      <ThemeToggle />
    </div>
    <div class="login-brand">
      <span class="brand-mark">APS</span>
      <div>
        <p class="brand-title">智能排产控制台</p>
        <p class="brand-subtitle">Smart APS · M3</p>
      </div>
    </div>

    <el-card shadow="never" class="login-card">
      <template #header>
        <div class="card-heading">
          <h1>登录</h1>
          <p>TOTP 未启用；JWT 有效期 8 小时，过期后需重新登录（本流不提供 refresh）。</p>
        </div>
      </template>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        show-icon
        :title="errorMessage"
        class="login-error"
      />

      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input
            v-model="username"
            placeholder="请输入用户名"
            autocomplete="username"
            clearable
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          native-type="submit"
          class="login-submit"
          :loading="submitting"
          :disabled="!canSubmit"
        >
          登录
        </el-button>
      </el-form>

      <p v-if="mockEnabled" class="mock-hint">
        Mock 调试账号：<code>admin / admin123</code>（输入错误密码可验证 40100 提示）
      </p>
      <p v-else class="mock-hint">
        错误码口径：40100 用户名或密码错误（含禁用/锁定）· 40300 权限不足（后端当前不返回 423）
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  padding: var(--space-10) var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-6);
  background: radial-gradient(circle at 20% 0%, var(--primary-bg), transparent 46%), var(--bg);
}

.login-theme {
  position: absolute;
  top: var(--space-5);
  right: var(--space-5);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  background: var(--primary-bg);
  border: var(--border-width) solid var(--primary);
  color: var(--primary);
  font-weight: 700;
  letter-spacing: var(--tracking-brand);
}

.brand-title {
  margin: 0;
  font-size: var(--font-title);
  font-weight: 600;
  color: var(--text);
}

.brand-subtitle {
  margin: 2px 0 0;
  font-size: var(--font-xs);
  color: var(--text-2);
}

.login-card {
  width: min(420px, 100%);
  background: var(--card);
  border: var(--border-width) solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-md);
}

.card-heading h1 {
  margin: 0;
  font-size: var(--font-title);
  color: var(--text);
}

.card-heading p {
  margin: var(--space-2) 0 0;
  font-size: var(--font-xs);
  color: var(--text-2);
  line-height: 1.6;
}

.login-error {
  margin-bottom: var(--space-4);
}

.login-submit {
  width: 100%;
  margin-top: var(--space-2);
}

.mock-hint {
  margin: var(--space-4) 0 0;
  font-size: var(--font-xs);
  color: var(--text-2);
  line-height: 1.6;
}

.mock-hint code {
  color: var(--text);
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
  padding: 1px 6px;
}
</style>
