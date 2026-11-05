package com.hengce.aps.service.process.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code routing_node.overrides} JSONB 的读写工具。
 *
 * <p>V1 的 routing_node 没有 primary_resource_id 列，节点级资源覆盖统一存放在
 * {@code overrides.primaryResourceId} / {@code overrides.secondaryResourceIds}（契约 C15）。</p>
 */
public final class RoutingOverrides {

    /** 主资源覆盖键。 */
    public static final String PRIMARY_RESOURCE_ID = "primaryResourceId";

    /** 辅助资源覆盖键。 */
    public static final String SECONDARY_RESOURCE_IDS = "secondaryResourceIds";

    private RoutingOverrides() {
    }

    /**
     * 构建覆盖对象，严格按三态写入（契约 C15 §20.3.1.1 / §20.3.1.5）。
     *
     * <p><strong>必须用 containsKey 语义：</strong></p>
     * <ul>
     *   <li>{@link RoutingNodeModes#INHERIT}：不写 {@code primaryResourceId} 键；</li>
     *   <li>{@link RoutingNodeModes#FLOAT}：写 {@code "primaryResourceId": null}，
     *       键必须存在（Jackson 默认包含 null 值），不能因"值为空"而省略；</li>
     *   <li>{@link RoutingNodeModes#FIXED}：写 UUID 字符串。</li>
     * </ul>
     *
     * @param mode 已规范化的主资源模式
     * @param primaryResourceId 主资源 UUID
     * @param secondaryResourceIds 辅助资源 UUID 列表，null 表示缺键/回退模板
     * @return JSONB 可序列化的覆盖对象
     */
    public static Map<String, Object> build(String mode, UUID primaryResourceId,
                                            List<UUID> secondaryResourceIds) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (RoutingNodeModes.FIXED.equals(mode)) {
            overrides.put(PRIMARY_RESOURCE_ID, primaryResourceId.toString());
        } else if (RoutingNodeModes.FLOAT.equals(mode)) {
            overrides.put(PRIMARY_RESOURCE_ID, null);
        }
        if (secondaryResourceIds != null) {
            List<String> values = new ArrayList<>(secondaryResourceIds.size());
            for (UUID id : secondaryResourceIds) {
                if (id != null) {
                    values.add(id.toString());
                }
            }
            overrides.put(SECONDARY_RESOURCE_IDS, values);
        }
        return overrides;
    }

    /**
     * 由 overrides 判定主资源模式。
     *
     * <p>判定必须基于 {@code containsKey}：缺键=INHERIT，键存在且值为 null=FLOAT，
     * 键存在且值为 UUID 字符串=FIXED。不能用 {@code get() == null} 判空，
     * 否则会把 INHERIT 与 FLOAT 混为一谈（契约 C15 §20.3.1.1）。</p>
     *
     * @param overrides 覆盖对象，可空
     * @return INHERIT/FLOAT/FIXED
     */
    public static String primaryResourceMode(Map<String, Object> overrides) {
        if (overrides == null || !overrides.containsKey(PRIMARY_RESOURCE_ID)) {
            return RoutingNodeModes.INHERIT;
        }
        return overrides.get(PRIMARY_RESOURCE_ID) == null
                ? RoutingNodeModes.FLOAT : RoutingNodeModes.FIXED;
    }

    /**
     * 读取主资源覆盖，仅用于展示/校验，不得用于装配判定。
     *
     * @param overrides 覆盖对象，可空
     * @return 主资源 UUID；缺键与显式 null 均返回 null（区分方式见
     *         {@link #primaryResourceMode(Map)}）
     */
    public static UUID primaryResourceId(Map<String, Object> overrides) {
        if (overrides == null) {
            return null;
        }
        Object value = overrides.get(PRIMARY_RESOURCE_ID);
        return value == null ? null : UUID.fromString(value.toString());
    }

    /**
     * 读取辅助资源覆盖，保持三态可区分。
     *
     * @param overrides 覆盖对象，可空
     * @return 缺键返回 null；{@code []} 返回空列表；数组返回显式列表
     */
    public static List<UUID> secondaryResourceIds(Map<String, Object> overrides) {
        if (overrides == null) {
            return null;
        }
        Object value = overrides.get(SECONDARY_RESOURCE_IDS);
        if (!(value instanceof List<?> raw)) {
            return null;
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null) {
                ids.add(UUID.fromString(item.toString()));
            }
        }
        return ids;
    }
}
