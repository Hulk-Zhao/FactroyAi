package com.hengce.aps.service.process.routing;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 节点主资源模式（契约 C15 §20.3.1.1 / §20.3.1.5）。
 *
 * <p>三态与存储映射：</p>
 * <ul>
 *   <li>{@link #INHERIT}：overrides 不写 {@code primaryResourceId} 键，装配侧回退工序模版；</li>
 *   <li>{@link #FLOAT}：overrides 写 {@code "primaryResourceId": null}（键存在、值为 null），
 *       表示显式浮动；</li>
 *   <li>{@link #FIXED}：overrides 写 UUID 字符串。</li>
 * </ul>
 *
 * <p><strong>旧请求兼容（重要，勿改为 FLOAT）：</strong>请求不带 {@code primaryResourceMode} 时，
 * 带 {@code primaryResourceId} 按 {@link #FIXED} 处理；{@code primaryResourceId} 也为空时按
 * {@link #INHERIT} 处理。旧契约无法表达 FLOAT，因此不能用「id 为空即 FLOAT」的推断。</p>
 */
final class RoutingNodeModes {

    /** 未覆盖，回退工序模版。 */
    static final String INHERIT = "INHERIT";

    /** 显式浮动，求解器可选任意资源。 */
    static final String FLOAT = "FLOAT";

    /** 固定主资源。 */
    static final String FIXED = "FIXED";

    private static final Set<String> SUPPORTED = Set.of(INHERIT, FLOAT, FIXED);

    private RoutingNodeModes() {
    }

    /**
     * 规范化并校验主资源模式。
     *
     * @param mode 原始模式，可空（旧请求）
     * @param primaryResourceId 原始主资源 UUID，可空
     * @return INHERIT/FLOAT/FIXED
     * @throws BizException 模式非法或 FIXED 缺 UUID 返回 42200；FLOAT 带 UUID 返回 42200
     */
    static String normalize(String mode, UUID primaryResourceId) {
        if (mode == null || mode.isBlank()) {
            return primaryResourceId == null ? INHERIT : FIXED;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "primaryResourceMode 不支持: " + mode);
        }
        if (FIXED.equals(normalized) && primaryResourceId == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "primaryResourceMode=FIXED 时必须提供 primaryResourceId");
        }
        if (FLOAT.equals(normalized) && primaryResourceId != null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "primaryResourceMode=FLOAT 时 primaryResourceId 必须为空");
        }
        return normalized;
    }
}
