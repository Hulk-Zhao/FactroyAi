## 变更说明

<!-- 说明本 PR 解决的问题、实现方式与关键取舍 -->

## 关联任务

<!-- 例如：P0-03 / FR-SE-001，或 Issue 链接 -->

## 变更类型

- [ ] 新功能（feat）
- [ ] 缺陷修复（fix）
- [ ] 重构（refactor）
- [ ] 文档（docs）
- [ ] 构建 / CI（build / ci）
- [ ] 其他（chore / test / style）

## 影响范围

<!-- 涉及的模块、API、数据库迁移、配置项；无影响可写「无」 -->

## 验证方式

<!-- 自测命令与结果，例如：mvn clean verify、npm run build、npm run lint -->

## 检查清单

- [ ] 已通过 `mvn clean verify`（含 Checkstyle / PMD）
- [ ] 前端变更已通过 `npm run lint`、`npm run format:check`、`npm run build`
- [ ] 模块依赖方向符合 `docs/coding-standard.md`，无 Timefold 类型泄漏
- [ ] 数据库字段 / 枚举取值与 `docs/db/schema.md` 一致（如有涉及）
- [ ] 新增 public 类型 / 方法已补充 Javadoc，日志使用 SLF4J 占位符
- [ ] 未提交密钥、本地配置、编译产物或 `db/` 下的临时改动
- [ ] 已同步更新相关文档与示例

## 回滚方案

<!-- 说明回滚步骤或开关；无特殊说明可写「revert 本 PR」 -->
