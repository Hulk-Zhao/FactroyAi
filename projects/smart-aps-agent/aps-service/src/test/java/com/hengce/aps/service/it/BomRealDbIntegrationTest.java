package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.masterdata.bom.BomTreeNodeView;
import com.hengce.aps.service.masterdata.bom.BomView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * BOM 真库集成测试：部分唯一索引、软删除后版本复用、环检测、多级树。
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class BomRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Test
    void partialUniqueIndexRejectsSecondActiveBom() {
        String parent = tag("MAT-P-ACTIVE");
        String child = tag("MAT-C-ACTIVE");
        createMaterial(parent);
        createMaterial(child);
        activate(createBom(parent, "V1", child));
        BomView draft = createBom(parent, "V2", child);
        UUID parentId = materialId(parent);

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE bom SET status = 'ACTIVE' WHERE id = ?", draft.id()));

        assertTrue(exception.getMessage().contains("uq_bom_one_active_per_parent"),
                "实际异常信息未包含索引名: " + exception.getMessage());
        long activeCount = count("SELECT count(*) FROM bom WHERE parent_material_id = ? "
                + "AND status = 'ACTIVE' AND deleted = false", parentId);
        assertEquals(1L, activeCount, "同一父件应始终只有一个 ACTIVE BOM");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT bom_version, status, deleted FROM bom WHERE parent_material_id = ? "
                        + "ORDER BY bom_version", parentId);
        evidence("SQL=UPDATE bom SET status='ACTIVE' WHERE id='" + draft.id()
                + "' -> " + exception.getClass().getSimpleName() + ": "
                + exception.getMessage().split("\n")[0]);
        evidence("SQL=SELECT bom_version,status,deleted FROM bom WHERE parent_material_id='"
                + parentId + "' -> " + rows);
        assertEquals("DRAFT", rows.get(1).get("status"), "被拒绝的第二个 ACTIVE 应回滚为 DRAFT");
    }

    @Test
    void softDeletedBomVersionCanBeReused() {
        String parent = tag("MAT-P-REUSE");
        String child = tag("MAT-C-REUSE");
        createMaterial(parent);
        createMaterial(child);
        BomView first = createBom(parent, "V1", child);
        bomService.delete(first.id());

        BomView reused = createBom(parent, "V1", child);

        UUID parentId = materialId(parent);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT bom_version, deleted FROM bom WHERE parent_material_id = ?", parentId);
        assertEquals(2, rows.size(), "软删除行与新行应同时存在");
        long live = count("SELECT count(*) FROM bom WHERE parent_material_id = ? AND deleted = false",
                parentId);
        assertEquals(1L, live);
        evidence("SQL=DELETE(bom.id=" + first.id() + ") 后重新创建 V1 -> bom.id=" + reused.id());
        evidence("SQL=SELECT bom_version,deleted FROM bom WHERE parent_material_id='" + parentId
                + "' -> " + rows);
    }

    @Test
    void multiLevelTreeExpandsToFourLevels() {
        String a = tag("MAT-TREE-A");
        String b = tag("MAT-TREE-B");
        String c = tag("MAT-TREE-C");
        String d = tag("MAT-TREE-D");
        createMaterial(a);
        createMaterial(b);
        createMaterial(c);
        createMaterial(d);
        BomView bomA = activate(createBom(a, "V1", b));
        activate(createBom(b, "V1", c));
        activate(createBom(c, "V1", d));

        BomTreeNodeView tree = bomService.tree(bomA.id());

        assertEquals(1, tree.level());
        assertEquals(a, tree.parentMaterialCode());
        BomTreeNodeView nodeB = tree.children().get(0);
        assertEquals(b, nodeB.parentMaterialCode());
        assertEquals(2, nodeB.level());
        BomTreeNodeView nodeC = nodeB.children().get(0);
        assertEquals(c, nodeC.parentMaterialCode());
        assertEquals(3, nodeC.level());
        BomTreeNodeView nodeD = nodeC.children().get(0);
        assertEquals(d, nodeD.parentMaterialCode());
        assertEquals(4, nodeD.level());
        assertNull(nodeD.bomId(), "叶子物料没有 ACTIVE BOM");
        assertTrue(nodeD.children().isEmpty());
        evidence("BOM 树(A=" + a + ", bomId=" + bomA.id() + ") -> " + render(tree));
    }

    @Test
    void treeDetectsCycleWithPath() {
        String a = tag("MAT-CYCLE-A");
        String b = tag("MAT-CYCLE-B");
        createMaterial(a);
        createMaterial(b);
        BomView bomA = activate(createBom(a, "V1", b));
        activate(createBom(b, "V1", a));

        BizException exception = assertThrows(BizException.class, () -> bomService.tree(bomA.id()));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("循环引用"), exception.getMessage());
        assertTrue(exception.getMessage().contains(a) && exception.getMessage().contains(b),
                exception.getMessage());
        evidence("BOM 树环检测 -> " + exception.getMessage());
    }

    private String render(BomTreeNodeView node) {
        StringBuilder builder = new StringBuilder();
        render(node, builder);
        return builder.toString();
    }

    private void render(BomTreeNodeView node, StringBuilder builder) {
        builder.append(node.parentMaterialCode()).append("(L").append(node.level()).append(')');
        if (!node.children().isEmpty()) {
            builder.append('[');
            for (int index = 0; index < node.children().size(); index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                render(node.children().get(index), builder);
            }
            builder.append(']');
        }
    }
}
