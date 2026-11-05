package com.hengce.aps.service.importexport;

import java.util.List;
import java.util.Map;

/**
 * 单实体导入器：负责行级校验与落库语义。
 *
 * <p>实现类只依赖 {@code importexport} 包内的通用行模型与既有只读服务/Mapper，
 * 保证 9 类实体复用同一套模板、解析、错误定位与幂等管线。</p>
 */
public interface EntityImportHandler {

    /**
     * 处理器对应的实体。
     *
     * @return 实体
     */
    ImportEntity entity();

    /**
     * 是否已接入落库执行；未接入时仅支持模板下载与预览校验。
     *
     * @return {@code true} 表示可执行落库
     */
    default boolean executable() {
        return true;
    }

    /**
     * 对单行做业务校验与落库前的规范化。
     *
     * @param row 行数据
     * @return 规范化后的值，供预览展示与落库使用
     * @throws ImportRowException 校验失败
     */
    Map<String, Object> validate(Map<String, String> row);

    /**
     * 落库单行；实现必须保证按业务键幂等（存在则更新，不存在则插入）。
     *
     * @param normalized 已通过校验的规范化值
     * @return 写入结果
     */
    WriteOutcome write(Map<String, Object> normalized);

    /**
     * 批量落库；默认逐行写入，多行合并为一条实体的场景（BOM、路线）覆盖此方法。
     *
     * @param rows 已通过校验的规范化行
     * @return 与写入实体一一对应的结果
     */
    default List<WriteOutcome> writeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::write).toList();
    }

    /**
     * 单行写入结果。
     *
     * @param inserted 是否新增
     * @param updated 是否更新
     */
    record WriteOutcome(boolean inserted, boolean updated) {

        /** 新增。 */
        public static final WriteOutcome INSERTED = new WriteOutcome(true, false);

        /** 更新。 */
        public static final WriteOutcome UPDATED = new WriteOutcome(false, true);

        /** 无变化。 */
        public static final WriteOutcome SKIPPED = new WriteOutcome(false, false);
    }
}
