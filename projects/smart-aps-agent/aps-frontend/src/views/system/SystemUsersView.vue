<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { createIdempotencyKey } from '@/api/http'
import {
  createRole,
  createUser,
  fetchMe,
  listPermissions,
  listRoles,
  listUsers,
  replaceRolePermissions,
  updateUser
} from '@/api/system'
import { ApiError } from '@/api/http'
import { DataTable } from '@/components/common'
import type {
  MeView,
  PermissionCode,
  PermissionView,
  RoleList,
  RoleView,
  User,
  UserStatus,
  UserUpdateRequest
} from '@/types/system'

const activeTab = ref<'users' | 'roles'>('users')

const users = ref<User[]>([])
const userPage = ref(1)
const userSize = ref(20)
const userTotal = ref(0)
const userLoading = ref(false)
const userError = ref('')
const statusFilter = ref('')

const roles = ref<RoleView[]>([])
const permissions = ref<PermissionView[]>([])
const roleLoading = ref(false)
const roleError = ref('')
const me = ref<MeView | null>(null)

const moduleLabels: Record<string, string> = {
  MD: '主数据',
  PR: '工艺',
  OM: '订单',
  SS: '策略',
  SE: '求解',
  SCHEDULE: '排程',
  SYS: '系统',
  AGENT: 'Agent'
}

const permissionsByModule = computed(() => {
  const groups = new Map<string, PermissionView[]>()
  permissions.value.forEach((permission) => {
    const key = permission.module || '其他'
    const list = groups.get(key) ?? []
    list.push(permission)
    groups.set(key, list)
  })
  return [...groups.entries()].map(([module, items]) => ({
    module,
    label: moduleLabels[module] ?? module,
    items
  }))
})

const roleOptions = computed(() => roles.value.map((role) => ({ value: role.code, label: role.name })))

function permissionName(code: string): string {
  return permissions.value.find((item) => item.code === code)?.name ?? code
}

function searchUsers() {
  userPage.value = 1
  void loadUsers()
}

async function loadUsers() {
  userLoading.value = true
  userError.value = ''
  try {
    const result = await listUsers({
      page: userPage.value,
      size: userSize.value,
      status: statusFilter.value || undefined
    })
    users.value = result.items
    userTotal.value = result.total
    userPage.value = result.page
    userSize.value = result.size
  } catch (exception) {
    users.value = []
    userTotal.value = 0
    userError.value = exception instanceof Error ? exception.message : '用户列表加载失败'
  } finally {
    userLoading.value = false
  }
}

async function loadRolesAndPermissions() {
  roleLoading.value = true
  roleError.value = ''
  try {
    // 注意契约形状不一致：GET /roles 返回 {items}，GET /permissions 返回裸数组。
    // 这里分别按真实形状解析，不把两者抹平成同一种结构。
    const [roleResult, permissionResult]: [RoleList, PermissionView[]] = await Promise.all([
      listRoles(),
      listPermissions()
    ])
    roles.value = roleResult.items
    permissions.value = permissionResult
  } catch (exception) {
    roleError.value = exception instanceof Error ? exception.message : '角色与权限加载失败'
  } finally {
    roleLoading.value = false
  }
}

async function loadMe() {
  try {
    me.value = await fetchMe()
  } catch {
    me.value = null
  }
}

onMounted(() => {
  void loadUsers()
  void loadRolesAndPermissions()
  void loadMe()
})

// ---------------- 用户新建 / 编辑 ----------------

interface UserFormState {
  id: string
  username: string
  displayName: string
  email: string
  phone: string
  password: string
  status: UserStatus
  roleCodes: string[]
  version: number
}

const userDialogVisible = ref(false)
const userDialogMode = ref<'create' | 'edit'>('create')
const savingUser = ref(false)
const userFormError = ref('')

const userForm = reactive<UserFormState>({
  id: '',
  username: '',
  displayName: '',
  email: '',
  phone: '',
  password: '',
  status: 'ACTIVE',
  roleCodes: ['VIEWER'],
  version: 1
})

function resetUserForm() {
  Object.assign(userForm, {
    id: '',
    username: '',
    displayName: '',
    email: '',
    phone: '',
    password: '',
    status: 'ACTIVE' as UserStatus,
    roleCodes: ['VIEWER'],
    version: 1
  })
  userFormError.value = ''
}

function openCreateUser() {
  resetUserForm()
  userDialogMode.value = 'create'
  userDialogVisible.value = true
}

function openEditUser(row: User) {
  Object.assign(userForm, {
    id: row.id,
    username: row.username,
    displayName: row.displayName ?? '',
    email: row.email ?? '',
    phone: row.phone ?? '',
    password: '',
    status: row.status,
    roleCodes: [...row.roleCodes],
    version: row.version
  })
  userFormError.value = ''
  userDialogMode.value = 'edit'
  userDialogVisible.value = true
}

async function submitUser() {
  userFormError.value = ''
  if (!userForm.username.trim()) {
    userFormError.value = '用户名不能为空'
    return
  }
  if (userDialogMode.value === 'create' && !userForm.password) {
    userFormError.value = '新建用户必须设置初始密码'
    return
  }
  savingUser.value = true
  try {
    if (userDialogMode.value === 'create') {
      await createUser(
        {
          username: userForm.username.trim(),
          displayName: userForm.displayName.trim() || undefined,
          email: userForm.email.trim() || undefined,
          phone: userForm.phone.trim() || undefined,
          password: userForm.password,
          roleCodes: userForm.roleCodes
        },
        createIdempotencyKey()
      )
      ElMessage.success('用户已创建')
    } else {
      const payload: UserUpdateRequest = {
        displayName: userForm.displayName.trim() || null,
        email: userForm.email.trim() || null,
        phone: userForm.phone.trim() || null,
        status: userForm.status,
        roleCodes: userForm.roleCodes,
        version: userForm.version
      }
      if (userForm.password) {
        payload.password = userForm.password
      }
      await updateUser(userForm.id, payload, createIdempotencyKey())
      ElMessage.success('用户已更新')
    }
    userDialogVisible.value = false
    await loadUsers()
  } catch (exception) {
    userFormError.value =
      exception instanceof ApiError && exception.code === 40901
        ? '该用户已被他人修改，请关闭后刷新列表再操作'
        : exception instanceof Error
          ? exception.message
          : '保存用户失败'
  } finally {
    savingUser.value = false
  }
}

// ---------------- 角色权限 ----------------

const roleDialogVisible = ref(false)
const roleDialogMode = ref<'create' | 'permissions'>('permissions')
const savingRole = ref(false)
const roleFormError = ref('')
const selectedRole = ref<RoleView | null>(null)

const roleForm = reactive({
  code: '',
  name: '',
  description: '',
  permissionCodes: [] as PermissionCode[]
})

function openCreateRole() {
  roleForm.code = ''
  roleForm.name = ''
  roleForm.description = ''
  roleForm.permissionCodes = []
  roleFormError.value = ''
  roleDialogMode.value = 'create'
  roleDialogVisible.value = true
}

function openRolePermissions(row: RoleView) {
  selectedRole.value = row
  roleForm.code = row.code
  roleForm.name = row.name
  roleForm.description = row.description ?? ''
  roleForm.permissionCodes = [...row.permissionCodes]
  roleFormError.value = ''
  roleDialogMode.value = 'permissions'
  roleDialogVisible.value = true
}

async function submitRole() {
  roleFormError.value = ''
  savingRole.value = true
  try {
    if (roleDialogMode.value === 'create') {
      if (!roleForm.code.trim() || !roleForm.name.trim()) {
        roleFormError.value = '角色码与角色名称不能为空'
        return
      }
      await createRole(
        {
          code: roleForm.code.trim(),
          name: roleForm.name.trim(),
          description: roleForm.description.trim() || undefined,
          permissionCodes: roleForm.permissionCodes
        },
        createIdempotencyKey()
      )
      ElMessage.success('角色已创建')
    } else if (selectedRole.value) {
      await replaceRolePermissions(
        selectedRole.value.id,
        roleForm.permissionCodes,
        createIdempotencyKey()
      )
      ElMessage.success(`角色 ${selectedRole.value.code} 权限已全量替换`)
    }
    roleDialogVisible.value = false
    await loadRolesAndPermissions()
    await loadMe()
  } catch (exception) {
    roleFormError.value = exception instanceof Error ? exception.message : '保存角色失败'
  } finally {
    savingRole.value = false
  }
}

function confirmRemoveAllPermissions() {
  if (roleForm.permissionCodes.length > 0) {
    return
  }
  void ElMessageBox.confirm('当前未勾选任何权限，保存后该角色将失去全部权限，继续？', '确认', {
    confirmButtonText: '继续保存',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      void submitRole()
    })
    .catch(() => undefined)
}
</script>

<template>
  <div>
    <h2 class="page-title">用户与角色</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="接口形状按真实契约解析：GET /roles → {items}；GET /permissions → 裸数组（不抹平）"
      description="权限点闭集 18 个、角色码闭集 5 个。用户角色变更走 PATCH /users/{userId}，roleCodes 为全量替换且 version 必填；角色权限走 PUT /roles/{roleId}/permissions，同样是全量替换。"
    />

    <el-tabs v-model="activeTab" class="section">
      <el-tab-pane label="用户" name="users">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>用户列表</span>
              <span class="muted" v-if="me">
                当前：{{ me.username }} · {{ me.permissions.length }} 项权限
              </span>
            </div>
          </template>

          <div class="toolbar-row">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 160px">
              <el-option label="启用 ACTIVE" value="ACTIVE" />
              <el-option label="禁用 DISABLED" value="DISABLED" />
              <el-option label="锁定 LOCKED" value="LOCKED" />
            </el-select>
            <el-button type="primary" @click="searchUsers">查询</el-button>
            <el-button @click="openCreateUser">新建用户</el-button>
          </div>

          <DataTable
            v-model:page="userPage"
            v-model:size="userSize"
            :data="users"
            :loading="userLoading"
            :error="userError"
            :total="userTotal"
            row-key="id"
            empty-text="该筛选下暂无用户"
            @change="loadUsers"
            @retry="loadUsers"
          >
            <el-table-column prop="username" label="用户名" min-width="130" />
            <el-table-column label="显示名" min-width="130">
              <template #default="{ row }">{{ row.displayName || '—' }}</template>
            </el-table-column>
            <el-table-column label="邮箱" min-width="180">
              <template #default="{ row }">{{ row.email || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  :type="row.status === 'ACTIVE' ? 'success' : row.status === 'LOCKED' ? 'danger' : 'info'"
                  size="small"
                >
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="角色" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="code in row.roleCodes" :key="code" size="small" class="role-tag">
                  {{ code }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="80">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditUser(row)">编辑</el-button>
              </template>
            </el-table-column>
          </DataTable>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="角色与权限" name="roles">
        <el-card v-loading="roleLoading" shadow="never">
          <template #header>
            <div class="card-header">
              <span>角色列表</span>
              <el-button type="primary" @click="openCreateRole">新建角色</el-button>
            </div>
          </template>
          <el-alert v-if="roleError" type="error" :closable="false" :title="roleError" class="section" />
          <DataTable
            :data="roles"
            :show-pagination="false"
            row-key="id"
            empty-text="暂无角色"
          >
            <el-table-column prop="code" label="角色码" width="160" />
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column label="说明" min-width="180">
              <template #default="{ row }">{{ row.description || '—' }}</template>
            </el-table-column>
            <el-table-column label="权限点" min-width="360">
              <template #default="{ row }">
                <el-tag
                  v-for="code in row.permissionCodes"
                  :key="code"
                  size="small"
                  type="info"
                  class="permission-tag"
                >
                  {{ permissionName(code) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="80">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRolePermissions(row)">编辑权限</el-button>
              </template>
            </el-table-column>
          </DataTable>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="userDialogVisible"
      :title="userDialogMode === 'create' ? '新建用户' : `编辑用户 · ${userForm.username}`"
      width="620px"
      destroy-on-close
    >
      <el-alert v-if="userFormError" type="error" :closable="false" :title="userFormError" class="dialog-error" />
      <el-form label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="userForm.username" :disabled="userDialogMode === 'edit'" maxlength="64" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="userForm.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="userForm.phone" maxlength="20" />
        </el-form-item>
        <el-form-item :label="userDialogMode === 'create' ? '初始密码（必填）' : '重置密码（留空则不修改）'">
          <el-input v-model="userForm.password" type="password" show-password autocomplete="off" />
        </el-form-item>
        <el-form-item v-if="userDialogMode === 'edit'" label="状态">
          <el-select v-model="userForm.status" style="width: 200px">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
            <el-option label="LOCKED" value="LOCKED" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色（全量替换）">
          <el-select v-model="userForm.roleCodes" multiple style="width: 100%">
            <el-option
              v-for="option in roleOptions"
              :key="option.value"
              :label="`${option.label}（${option.value}）`"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingUser" @click="submitUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="roleDialogVisible"
      :title="roleDialogMode === 'create' ? '新建角色' : `编辑权限 · ${roleForm.code}`"
      width="720px"
      destroy-on-close
    >
      <el-alert v-if="roleFormError" type="error" :closable="false" :title="roleFormError" class="dialog-error" />
      <el-form label-position="top">
        <el-form-item v-if="roleDialogMode === 'create'" label="角色码">
          <el-input v-model="roleForm.code" maxlength="32" placeholder="例如 QC_ENGINEER" />
        </el-form-item>
        <el-form-item v-if="roleDialogMode === 'create'" label="角色名称">
          <el-input v-model="roleForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="roleDialogMode === 'create'" label="说明">
          <el-input v-model="roleForm.description" maxlength="128" />
        </el-form-item>
        <el-form-item label="权限点（全量替换，可清空）">
          <div class="permission-groups">
            <div v-for="group in permissionsByModule" :key="group.module" class="permission-group">
              <p class="group-title">{{ group.label }}（{{ group.module }}）</p>
              <el-checkbox-group v-model="roleForm.permissionCodes">
                <el-checkbox
                  v-for="permission in group.items"
                  :key="permission.code"
                  :value="permission.code"
                >
                  {{ permission.name }} <span class="code-hint">{{ permission.code }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button
          v-if="roleDialogMode === 'permissions'"
          type="warning"
          plain
          :loading="savingRole"
          @click="confirmRemoveAllPermissions"
        >
          清空并保存
        </el-button>
        <el-button type="primary" :loading="savingRole" @click="submitRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 var(--space-4);
  font-size: var(--font-title);
  color: var(--text);
}

.hint-alert {
  margin-bottom: var(--space-4);
}

.section {
  margin-bottom: var(--space-4);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.muted {
  color: var(--text-2);
  font-size: var(--font-xs);
}

.toolbar-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-3);
}

.role-tag,
.permission-tag {
  margin: 2px 6px 2px 0;
}

.dialog-error {
  margin-bottom: var(--space-3);
}

.permission-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  width: 100%;
}

.permission-group {
  border: var(--border-width) solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--space-3);
  background: var(--bg-soft);
}

.group-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-xs);
  color: var(--text-2);
}

.code-hint {
  color: var(--text-3);
  font-size: var(--font-xs);
}
</style>
