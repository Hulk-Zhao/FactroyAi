package com.hengce.aps.service.importexport;

import java.util.List;

/**
 * 支持 Excel 导入导出的实体及其列定义。
 *
 * <p>M2-BD-05 已定契约（C26）：materials / boms / resources / calendars /
 * operationTemplates / routings / orders / strategies / suppliers；C38 追加 customers。</p>
 */
public enum ImportEntity {

    /** 物料主数据。 */
    MATERIALS("materials", "物料", List.of(
            ImportColumn.text("code", "物料编码", true, "MAT-0001", "唯一编码，最长 64"),
            ImportColumn.text("name", "物料名称", true, "铝合金外壳", "最长 128"),
            ImportColumn.enumOf("type", "物料类型", true, "FINISHED", "物料类型",
                    "FINISHED", "SEMI", "RAW", "AUX", "PACK"),
            ImportColumn.text("spec", "规格型号", false, "120x80mm", "最长 128"),
            ImportColumn.text("unit", "基本单位", true, "PCS", "最长 16"),
            ImportColumn.number("safetyStock", "安全库存", false, ImportDataType.DECIMAL, "10", "非负数"),
            ImportColumn.number("moq", "最小起订量", false, ImportDataType.DECIMAL, "1", "非负数"),
            ImportColumn.number("leadTimeDays", "采购提前期(天)", false, ImportDataType.INTEGER, "3", "非负整数"))),

    /**
     * BOM：同一父件 + 版本的多行合并为一个 BOM，每行代表一条子件。
     */
    BOMS("boms", "BOM", List.of(
            ImportColumn.text("parentMaterialCode", "父件物料编码", true, "FG-0001", "已存在的物料编码"),
            ImportColumn.text("bomVersion", "BOM版本", true, "V1", "业务版本号"),
            ImportColumn.date("effectiveDate", "生效日期", true, "2026-01-01", "yyyy-MM-dd"),
            ImportColumn.date("expiryDate", "失效日期", false, "2027-01-01", "yyyy-MM-dd，可空"),
            ImportColumn.bool("isPanel", "是否拼板", false, "false", "true/false"),
            ImportColumn.number("panelSize", "拼板数量", false, ImportDataType.INTEGER, "1", "正整数"),
            ImportColumn.text("componentMaterialCode", "子件物料编码", true, "RM-0001", "已存在的物料编码"),
            ImportColumn.number("componentQty", "子件数量", true, ImportDataType.DECIMAL, "2", "正数"),
            ImportColumn.number("scrapRate", "损耗率(%)", false, ImportDataType.DECIMAL, "2", "百分比 0-100"))),

    /** 资源。 */
    RESOURCES("resources", "资源", List.of(
            ImportColumn.text("code", "资源编码", true, "RES-0001", "唯一编码，最长 64"),
            ImportColumn.text("name", "资源名称", true, "CNC-01", "最长 128"),
            ImportColumn.enumOf("type", "资源类型", true, "PRIMARY", "资源类型",
                    "PRIMARY", "SECONDARY", "HUMAN", "TOOL"),
            ImportColumn.enumOf("capacityMode", "产能模式", true, "UNIT_PER_HOUR", "产能模式",
                    "UNIT_PER_HOUR", "SECONDS_PER_UNIT", "BATCH_SECONDS"),
            ImportColumn.number("capacityValue", "产能值", false, ImportDataType.DECIMAL, "10", "非负数"),
            ImportColumn.number("batchCapacity", "批量", false, ImportDataType.INTEGER, "1", "正整数"),
            ImportColumn.number("efficiency", "效率系数", false, ImportDataType.DECIMAL, "1.0", "大于 0"),
            ImportColumn.number("setupSeconds", "换型工时(秒)", false, ImportDataType.INTEGER, "0", "非负整数"),
            ImportColumn.number("teardownSeconds", "收尾工时(秒)", false, ImportDataType.INTEGER, "0",
                    "非负整数"))),

    /** 日历。 */
    CALENDARS("calendars", "日历", List.of(
            ImportColumn.text("name", "日历名称", true, "标准三班", "同一租户内名称+版本唯一"),
            ImportColumn.number("calendarVersion", "日历版本", true, ImportDataType.INTEGER, "1", "正整数"),
            ImportColumn.enumOf("type", "日历类型", true, "FACTORY", "日历类型",
                    "FACTORY", "RESOURCE", "SHIFT"),
            ImportColumn.text("resourceCode", "资源编码", false, "RES-0001", "RESOURCE/SHIFT 类型必填，按资源编码绑定"),
            ImportColumn.date("effectiveFrom", "生效日期", true, "2026-01-01", "yyyy-MM-dd"),
            ImportColumn.date("effectiveTo", "失效日期", false, "2027-01-01", "yyyy-MM-dd，可空"),
            ImportColumn.json("shiftsJson", "班次JSON", true, "[{\"start\":\"08:00\",\"end\":\"16:00\"}]",
                    "班次数组，JSON 文本"),
            ImportColumn.json("holidaysJson", "节假日JSON", false, "[]", "节假日数组，JSON 文本"))),

    /** 工序模版。 */
    OPERATION_TEMPLATES("operationTemplates", "工序模版", List.of(
            ImportColumn.text("code", "工序编码", true, "OP-0001", "唯一编码"),
            ImportColumn.text("name", "工序名称", true, "粗车", "最长 128"),
            ImportColumn.text("primaryResourceCode", "主资源编码", false, "RES-0001", "已存在的资源编码"),
            ImportColumn.number("standardSeconds", "标准工时(秒)", false, ImportDataType.INTEGER, "60", "非负整数"),
            ImportColumn.number("setupSeconds", "换型工时(秒)", false, ImportDataType.INTEGER, "0", "非负整数"),
            ImportColumn.number("teardownSeconds", "收尾工时(秒)", false, ImportDataType.INTEGER, "0", "非负整数"),
            ImportColumn.number("templateVersion", "模版版本", false, ImportDataType.INTEGER, "1", "正整数"))),

    /**
     * 工艺路线：同一产品 + 版本的多行合并为一条路线，带 seqNo 的行生成节点。
     */
    ROUTINGS("routings", "工艺路线", List.of(
            ImportColumn.text("productCode", "产品物料编码", true, "FG-0001", "已存在的物料编码"),
            ImportColumn.text("routingVersion", "路线版本", true, "V1", "业务版本号"),
            ImportColumn.date("effectiveDate", "生效日期", true, "2026-01-01", "yyyy-MM-dd"),
            ImportColumn.date("expiryDate", "失效日期", false, "2027-01-01", "yyyy-MM-dd，可空"),
            ImportColumn.number("seqNo", "节点序号", false, ImportDataType.INTEGER, "10", "正整数；填写后该行生成路线节点"),
            ImportColumn.number("fromSeqNo", "前驱节点序号", false, ImportDataType.INTEGER, "10",
                    "可空；填写后与同路线内该序号节点建 FS 边"),
            ImportColumn.text("operationCode", "工序编码", false, "OP-0001", "有节点序号时必填"),
            ImportColumn.text("operationTemplateCode", "工序模版编码", false, "OP-0001", "已存在的工序模版编码"),
            ImportColumn.number("standardSeconds", "标准工时(秒)", false, ImportDataType.INTEGER, "60", "非负整数"),
            ImportColumn.text("primaryResourceCode", "主资源编码", false, "RES-0001", "已存在的资源编码"))),

    /** 订单。 */
    ORDERS("orders", "订单", List.of(
            ImportColumn.text("orderNo", "订单号", true, "SO-0001", "同一租户内唯一"),
            ImportColumn.enumOf("source", "订单来源", false, "MANUAL", "订单来源",
                    "ERP_SYNC", "MANUAL", "AGENT"),
            ImportColumn.text("materialCode", "产品物料编码", true, "FG-0001", "已存在的物料编码"),
            ImportColumn.number("qty", "数量", true, ImportDataType.DECIMAL, "100", "大于 0"),
            ImportColumn.date("dueDate", "交期", true, "2026-12-31", "yyyy-MM-dd"),
            ImportColumn.enumOf("priority", "优先级", false, "NORMAL", "优先级",
                    "URGENT", "HIGH", "NORMAL", "LOW"),
            ImportColumn.text("remark", "备注", false, "首单试产", "自由文本"),
            ImportColumn.text("customerCode", "客户编码", false, "CUS-0001",
                    "可选；按 customer.code 解析，未命中整行 42200；留空=未指定客户"))),

    /** 策略。 */
    STRATEGIES("strategies", "策略", List.of(
            ImportColumn.text("name", "策略名称", true, "标准策略", "名称+版本唯一"),
            ImportColumn.enumOf("direction", "排产方向", true, "FORWARD", "排产方向",
                    "FORWARD", "BACKWARD", "BOTTLENECK"),
            ImportColumn.number("strategyVersion", "策略版本", true, ImportDataType.INTEGER, "1", "正整数"),
            ImportColumn.date("effectiveFrom", "生效日期", true, "2026-01-01", "yyyy-MM-dd"),
            ImportColumn.date("effectiveTo", "失效日期", false, "2027-01-01", "yyyy-MM-dd，可空"))),

    /** 供应商。 */
    SUPPLIERS("suppliers", "供应商", List.of(
            ImportColumn.text("code", "供应商编码", true, "SUP-0001", "唯一编码，最长 64"),
            ImportColumn.text("name", "供应商名称", true, "衡策精工", "最长 256"),
            ImportColumn.number("leadTimeDays", "采购提前期(天)", false, ImportDataType.INTEGER, "7", "非负整数"),
            ImportColumn.number("moq", "最小起订量", false, ImportDataType.DECIMAL, "1", "非负数"),
            ImportColumn.enumOf("qualityGrade", "质量等级", false, "A", "质量等级", "A", "B", "C"),
            ImportColumn.bool("blacklisted", "是否黑名单", false, "false", "true/false"))),

    /**
     * 换型矩阵（C43）：FROM/TO 产品-程序成组，支持默认值 + 同/跨系列特例，
     * 生效范围按租户/资源组/资源三级；业务键已存在则更新。
     */
    SETUP_MATRICES("setupMatrices", "换型矩阵", List.of(
            ImportColumn.enumOf("keyType", "维度", true, "PRODUCT", "PRODUCT=产品物料编码，PROGRAM=程序/工序编码",
                    "PRODUCT", "PROGRAM"),
            ImportColumn.text("fromKey", "FROM键", false, "MAT-0001", "为空表示通配默认；最长 64"),
            ImportColumn.text("toKey", "TO键", false, "MAT-0002", "为空表示通配默认；最长 64"),
            ImportColumn.enumOf("scopeType", "生效范围", false, "TENANT", "生效范围",
                    "TENANT", "RESOURCE_GROUP", "RESOURCE"),
            ImportColumn.text("resourceCode", "资源编码", false, "RES-0001", "RESOURCE 范围必填"),
            ImportColumn.text("resourceGroupCode", "资源组编码", false, "GRP-0001", "RESOURCE_GROUP 范围必填"),
            ImportColumn.number("setupSeconds", "默认换型秒数", true, ImportDataType.INTEGER, "1800", "非负整数"),
            ImportColumn.number("sameSeriesSeconds", "同系列换型秒数", false, ImportDataType.INTEGER, "900",
                    "可空；为空回退默认值"),
            ImportColumn.number("crossSeriesSeconds", "跨系列换型秒数", false, ImportDataType.INTEGER, "3600",
                    "可空；为空回退默认值"),
            ImportColumn.text("remark", "备注", false, "同系列快速换型", "最长 255"))),

    /** 客户（C38，slug 为复数，与既有约定一致）。 */
    CUSTOMERS("customers", "客户", List.of(
            ImportColumn.text("code", "客户编码", true, "CUS-0001", "唯一编码，最长 64；已存在则更新"),
            ImportColumn.text("name", "客户名称", true, "衡策客户", "最长 256"),
            ImportColumn.enumOf("priority", "优先级", false, "NORMAL", "优先级", "VIP", "NORMAL", "LOW"),
            ImportColumn.number("leadTimeDays", "交付提前期(天)", false, ImportDataType.INTEGER, "7", "非负整数"),
            ImportColumn.text("paymentTerms", "付款条件", false, "月结30天", "最长 128"),
            ImportColumn.bool("blacklisted", "是否黑名单", false, "false", "true/false")));

    private final String slug;

    private final String label;

    private final List<ImportColumn> columns;

    ImportEntity(String slug, String label, List<ImportColumn> columns) {
        this.slug = slug;
        this.label = label;
        this.columns = columns;
    }

    /**
     * URL 路径中的实体标识。
     *
     * @return 实体 slug
     */
    public String slug() {
        return slug;
    }

    /**
     * 中文名称，用于模板与错误提示。
     *
     * @return 中文名称
     */
    public String label() {
        return label;
    }

    /**
     * 列定义，顺序即模板列顺序。
     *
     * @return 列定义
     */
    public List<ImportColumn> columns() {
        return columns;
    }

    /**
     * 按列键取列定义。
     *
     * @param key 列键
     * @return 列定义
     * @throws IllegalArgumentException 列键不存在
     */
    public ImportColumn column(String key) {
        return columns.stream()
                .filter(column -> column.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知列: " + slug + "." + key));
    }

    /**
     * 按 slug 解析实体。
     *
     * @param slug URL 路径中的实体标识
     * @return 实体；未匹配返回 {@code null}
     */
    public static ImportEntity fromSlug(String slug) {
        for (ImportEntity entity : values()) {
            if (entity.slug.equalsIgnoreCase(slug)) {
                return entity;
            }
        }
        return null;
    }
}
