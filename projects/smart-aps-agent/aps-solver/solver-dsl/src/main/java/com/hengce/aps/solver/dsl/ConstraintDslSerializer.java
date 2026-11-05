package com.hengce.aps.solver.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * DSL 规范化序列化器：永远输出数组 {@code constraints}，兼容别名只在解析侧存在。
 *
 * <p>规则项按 C19 冻结字段输出 {@code id/ruleCode/type/weight/enabled/params}。
 * 注意：对外（YAML / API）的规则字段名是 {@code ruleCode}，而 {@code compiled_spec}
 * 内部仍使用 {@code code}，两者是同一份数据的两种表示，不要当成两套字段。</p>
 */
public final class ConstraintDslSerializer {

    private final Yaml yaml;

    /**
     * 构造函数。
     */
    public ConstraintDslSerializer() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    /**
     * 序列化为规范化 YAML。
     *
     * @param document DSL 文档
     * @return YAML 文本（含 {@code constraints} 数组）
     */
    public String serialize(ConstraintDslDocument document) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", document.version() + ".0");
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConstraintDslSpec spec : document.constraints()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", spec.ruleCode().toLowerCase(Locale.ROOT));
            item.put("ruleCode", spec.ruleCode());
            item.put("type", "SOFT");
            item.put("weight", spec.weight());
            item.put("enabled", spec.weight() > 0);
            if (!spec.params().isEmpty()) {
                item.put("params", new LinkedHashMap<>(spec.params()));
            }
            items.add(item);
        }
        root.put("constraints", items);
        return yaml.dump(root);
    }
}
