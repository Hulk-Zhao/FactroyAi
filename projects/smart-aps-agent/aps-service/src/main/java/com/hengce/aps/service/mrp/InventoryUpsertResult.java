package com.hengce.aps.service.mrp;

/**
 * 库存批量 upsert 结果（C25）。
 *
 * <p>契约响应仅暴露 {@code accepted}/{@code rejected} 两个计数。当前 upsert 为整体事务：
 * 任一条校验失败即整批回滚并返回对应错误码，因此成功响应中 {@code rejected} 恒为 0，
 * {@code accepted} 为本次提交的物料+仓库组合数。</p>
 *
 * @param accepted 受理（新增或更新）的记录数
 * @param rejected 被拒绝的记录数
 */
public record InventoryUpsertResult(int accepted, int rejected) {
}
