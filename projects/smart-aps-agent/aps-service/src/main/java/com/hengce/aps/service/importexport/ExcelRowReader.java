package com.hengce.aps.service.importexport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Excel 读取器：把首个工作表解析为“行号 + 列键/值”列表。
 *
 * <p>表头按 {@link ImportColumn#header()} 精确匹配，同时兼容列键与序号顺序；
 * 空行跳过，表头缺失或多出未知列在读取阶段形成头部错误。</p>
 */
@Component
public class ExcelRowReader {

    /**
     * 读取结果。
     *
     * @param rows 数据行
     * @param errors 表头级错误
     */
    public record ReadResult(List<RowData> rows, List<ImportError> errors) {
    }

    /**
     * 单行数据。
     *
     * @param rowNumber Excel 物理行号，从 1 开始
     * @param values 列键到文本值
     */
    public record RowData(int rowNumber, Map<String, String> values) {
    }

    /**
     * 读取 xlsx。
     *
     * @param input 文件流
     * @param entity 实体
     * @return 读取结果
     */
    public ReadResult read(InputStream input, ImportEntity entity) {
        try (Workbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                return new ReadResult(List.of(), List.of(new ImportError(1, "header", 42200, "Excel 缺少工作表")));
            }
            Sheet sheet = workbook.getSheetAt(0);
            return readSheet(sheet, entity);
        } catch (IOException exception) {
            throw new ImportRowException("file", 42200, "Excel 文件无法解析，请确认为 xlsx 格式");
        }
    }

    private ReadResult readSheet(Sheet sheet, ImportEntity entity) {
        Row header = sheet.getRow(0);
        if (header == null) {
            return new ReadResult(List.of(), List.of(new ImportError(1, "header", 42200, "缺少表头行")));
        }
        Map<Integer, String> columnIndex = resolveColumns(header, entity);
        List<ImportError> errors = new ArrayList<>();
        for (String key : missingRequiredColumns(columnIndex, entity)) {
            errors.add(new ImportError(1, key, 42200, "缺少必需列: " + columnHeader(entity, key)));
        }
        if (!errors.isEmpty()) {
            return new ReadResult(List.of(), errors);
        }
        List<RowData> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean blank = true;
            for (Map.Entry<Integer, String> entry : columnIndex.entrySet()) {
                String value = format(row.getCell(entry.getKey()), formatter);
                values.put(entry.getValue(), value);
                if (!value.isBlank()) {
                    blank = false;
                }
            }
            if (!blank) {
                rows.add(new RowData(index + 1, values));
            }
        }
        return new ReadResult(rows, errors);
    }

    private Map<Integer, String> resolveColumns(Row header, ImportEntity entity) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String text = format(header.getCell(index), formatter);
            if (text.isBlank()) {
                continue;
            }
            for (ImportColumn column : entity.columns()) {
                if (text.equalsIgnoreCase(column.header()) || text.equalsIgnoreCase(column.key())
                        || text.equalsIgnoreCase(column.header() + " *")) {
                    mapping.put(index, column.key());
                    break;
                }
            }
        }
        return mapping;
    }

    private List<String> missingRequiredColumns(Map<Integer, String> columnIndex, ImportEntity entity) {
        List<String> missing = new ArrayList<>();
        for (ImportColumn column : entity.columns()) {
            if (column.required() && !columnIndex.containsValue(column.key())) {
                missing.add(column.key());
            }
        }
        return missing;
    }

    private String columnHeader(ImportEntity entity, String key) {
        return entity.columns().stream()
                .filter(column -> column.key().equals(key))
                .findFirst()
                .map(ImportColumn::header)
                .orElse(key);
    }

    private String format(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return formatter.formatCellValue(cell).trim();
        }
        return formatter.formatCellValue(cell).trim();
    }
}
